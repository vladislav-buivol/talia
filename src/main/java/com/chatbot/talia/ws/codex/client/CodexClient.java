package com.chatbot.talia.ws.codex.client;

import com.chatbot.talia.ws.codex.protocol.CodexMessageCodec;
import com.chatbot.talia.ws.codex.protocol.handler.CodexMessageHandlerRegistry;
import com.chatbot.talia.ws.codex.protocol.request.CodexModel;
import com.chatbot.talia.ws.codex.protocol.request.ThreadListRequest;
import com.chatbot.talia.ws.codex.protocol.request.ThreadResumeRequest;
import com.chatbot.talia.ws.codex.protocol.request.ThreadStartRequest;
import com.chatbot.talia.ws.codex.protocol.request.ThreadTurnsListRequest;
import com.chatbot.talia.ws.codex.protocol.request.TurnStartRequest;
import com.chatbot.talia.ws.codex.protocol.response.ThreadListResponse;
import com.chatbot.talia.ws.codex.protocol.response.ThreadResumeResponse;
import com.chatbot.talia.ws.codex.protocol.response.ThreadStartResponse;
import com.chatbot.talia.ws.codex.protocol.response.ThreadTurnsListResponse;
import com.chatbot.talia.ws.codex.protocol.response.ThreadUnsubscribeResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

@Component
@Profile("codex")
public class CodexClient implements AutoCloseable {
    private final CodexWebSocketClient rpc;

    public CodexClient(CodexWebSocketClient rpc) {
        this.rpc = Objects.requireNonNull(rpc);
    }

    public static CodexClient create(String serverUrl) {
        CodexMessageCodec codec = new CodexMessageCodec(new ObjectMapper());
        CodexMessageHandlerRegistry registry = CodexMessageHandlerRegistry.create(new CodexMessageReporter());
        return new CodexClient(new CodexWebSocketClient(serverUrl, codec, registry));
    }

    /** Accesses the typed RPC API on this client's session. */
    public CodexWebSocketClient rpc() {
        return rpc;
    }

    public CompletableFuture<Void> connect(String name, String title, String version) {
        return rpc.connectAndInitialize(name, title, version).thenApply(response -> null);
    }

    public CompletableFuture<String> startConversation(CodexModel model) {
        return rpc.send(ThreadStartRequest.create(model), ThreadStartResponse.class)
                .thenApply(CodexClient::startedThreadId);
    }

    public CompletableFuture<String> resumeConversation(String threadId) {
        Objects.requireNonNull(threadId);
        return rpc.send(ThreadResumeRequest.create(threadId), ThreadResumeResponse.class)
                .thenApply(CodexClient::resumedThreadId);
    }

    /** Unsubscribes this session and preserves the stored conversation. */
    public CompletableFuture<String> finishConversation(String threadId) {
        return rpc.unsubscribeThread(threadId).thenApply(CodexClient::unsubscribeStatus);
    }

    public CompletableFuture<ConversationPage> listConversations(int limit) {
        return listConversations(limit, null);
    }

    public CompletableFuture<ConversationPage> listConversations(int limit, String cursor) {
        if (limit < 1 || limit > 100) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Conversation page size must be 1 to 100"));
        }
        return rpc.send(ThreadListRequest.create(limit, cursor), ThreadListResponse.class)
                .thenApply(CodexClient::conversationPage);
    }

    /** Streams text on the WebSocket listener thread and returns the terminal turn outcome. */
    public CompletableFuture<CodexTurnResult> runTurn(String threadId, String prompt, Consumer<String> onText) {
        return rpc.runTurn(TurnStartRequest.create(threadId, prompt), onText);
    }

    public ConversationHistory openConversation(String threadId, int messageLimit, ConversationHistory.Order order)
            throws InterruptedException, ExecutionException {
        Objects.requireNonNull(order);
        if (messageLimit < 0) {
            throw new IllegalArgumentException("Message limit cannot be negative");
        }
        return getConversationHistory(resumeConversation(threadId).get(), messageLimit, order);
    }

    /** Reads up to messageLimit user and assistant messages without resuming the conversation. */
    public ConversationHistory getConversationHistory(String threadId, int messageLimit, ConversationHistory.Order order)
            throws InterruptedException, ExecutionException {
        Objects.requireNonNull(threadId);
        Objects.requireNonNull(order);
        if (messageLimit < 0) {
            throw new IllegalArgumentException("Message limit cannot be negative");
        }

        boolean newestFirst = order == ConversationHistory.Order.NEWEST_FIRST;
        List<ConversationHistory.Message> messages = new ArrayList<>();
        String cursor = null;
        while (messages.size() < messageLimit) {
            ThreadTurnsListRequest.Params params = new ThreadTurnsListRequest.Params(
                    threadId, cursor, Math.min(10, messageLimit - messages.size()), newestFirst ? "desc" : "asc", "full");
            ThreadTurnsListResponse.Result page = rpc.send(new ThreadTurnsListRequest(null, params),
                    ThreadTurnsListResponse.class).get().result();
            for (ThreadTurnsListResponse.Turn turn : page.data()) {
                List<ThreadTurnsListResponse.Item> items = newestFirst ? turn.items().reversed() : turn.items();
                for (ThreadTurnsListResponse.Item item : items) {
                    ConversationHistory.Message message = historyMessage(item);
                    if (message != null) {
                        messages.add(message);
                        if (messages.size() == messageLimit) {
                            return new ConversationHistory(threadId, messages);
                        }
                    }
                }
            }
            if (page.nextCursor() == null) {
                break;
            }
            cursor = nextCursor(cursor, page.nextCursor());
        }
        return new ConversationHistory(threadId, messages);
    }

    /** Reads histories for up to conversationCount recently updated conversations. */
    public List<ConversationHistory> getConversationHistory(
            int conversationCount, int messageLimit, ConversationHistory.Order order)
            throws InterruptedException, ExecutionException {
        if (conversationCount < 0 || messageLimit < 0) {
            throw new IllegalArgumentException("Conversation and message limits cannot be negative");
        }
        Objects.requireNonNull(order);

        List<ConversationHistory> histories = new ArrayList<>();
        String cursor = null;
        while (histories.size() < conversationCount) {
            ConversationPage page = listConversations(Math.min(100, conversationCount - histories.size()), cursor).get();
            for (ConversationSummary conversation : page.conversations()) {
                histories.add(getConversationHistory(conversation.id(), messageLimit, order));
                if (histories.size() == conversationCount) {
                    return List.copyOf(histories);
                }
            }
            if (page.nextCursor() == null) {
                break;
            }
            cursor = nextCursor(cursor, page.nextCursor());
        }
        return List.copyOf(histories);
    }

    /** Returns the first user message if it contains only text, or null. The timeout applies per page. */
    public String getFirstUserText(String threadId, Duration pageTimeout)
            throws InterruptedException, ExecutionException, TimeoutException {
        Objects.requireNonNull(threadId);
        long timeoutNanos = pageTimeout.toNanos();
        String cursor = null;
        do {
            ThreadTurnsListResponse.Result page = rpc.send(ThreadTurnsListRequest.create(threadId, cursor),
                    ThreadTurnsListResponse.class).get(timeoutNanos, TimeUnit.NANOSECONDS).result();
            for (ThreadTurnsListResponse.Turn turn : page.data()) {
                for (ThreadTurnsListResponse.Item item : turn.items()) {
                    if ("userMessage".equals(item.type())) {
                        return userText(item.content(), true);
                    }
                }
            }
            cursor = page.nextCursor() == null ? null : nextCursor(cursor, page.nextCursor());
        } while (cursor != null);
        return null;
    }

    @Override
    public void close() {
        rpc.close();
    }

    public CompletableFuture<Void> closeAsync() {
        return rpc.closeAsync();
    }

    /** Closes the connection after Codex acknowledges the unsubscribe and returns its status. */
    public CompletableFuture<String> finishConversationAndCloseAsync(String threadId) {
        return finishConversation(threadId)
                .thenCompose(status -> closeAsync().thenApply(closed -> status));
    }

    private static String startedThreadId(ThreadStartResponse response) {
        return response.result().thread().id();
    }

    private static String resumedThreadId(ThreadResumeResponse response) {
        return response.result().thread().id();
    }

    private static String unsubscribeStatus(ThreadUnsubscribeResponse response) {
        return response.result().status();
    }

    private static ConversationPage conversationPage(ThreadListResponse response) {
        List<ConversationSummary> conversations = new ArrayList<>();
        for (ThreadListResponse.ThreadInfo thread : response.result().data()) {
            conversations.add(new ConversationSummary(thread.id(), thread.name(), thread.preview(), thread.updatedAt()));
        }
        return new ConversationPage(conversations, response.result().nextCursor());
    }

    private static String nextCursor(String current, String next) {
        if (next.equals(current)) {
            throw new IllegalStateException("Codex returned the same history cursor twice");
        }
        return next;
    }

    private static ConversationHistory.Message historyMessage(ThreadTurnsListResponse.Item item) {
        if ("agentMessage".equals(item.type())) {
            return new ConversationHistory.Message(ConversationHistory.Role.ASSISTANT, item.text(), null);
        }
        if (!"userMessage".equals(item.type())) {
            return null;
        }
        return new ConversationHistory.Message(ConversationHistory.Role.USER, userText(item.content(), false), item.content());
    }

    private static String userText(JsonNode content, boolean textOnly) {
        if (content == null || (textOnly && !content.isArray())) {
            return textOnly ? null : "";
        }
        StringBuilder text = new StringBuilder();
        for (JsonNode input : content) {
            if ("text".equals(input.path("type").asString()) && input.path("text").isString()) {
                text.append(input.path("text").asString());
            } else if (textOnly) {
                return null;
            }
        }
        return text.toString();
    }

    public record ConversationPage(List<ConversationSummary> conversations, String nextCursor) {
        public ConversationPage {
            conversations = List.copyOf(conversations);
        }
    }

    public record ConversationSummary(String id, String name, String preview, Long updatedAt) {
    }
}
