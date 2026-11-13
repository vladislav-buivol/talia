package com.chatbot.talia.ws.codex.client;

import com.chatbot.talia.ws.codex.protocol.CodexMessageCodec;
import com.chatbot.talia.ws.codex.protocol.handler.CodexMessageHandlerRegistry;
import com.chatbot.talia.ws.codex.protocol.request.CodexMethod;
import com.chatbot.talia.ws.codex.protocol.request.CodexModel;
import com.chatbot.talia.ws.codex.protocol.request.CodexNotification;
import com.chatbot.talia.ws.codex.protocol.request.CodexRequest;
import com.chatbot.talia.ws.codex.protocol.request.InitializeRequest;
import com.chatbot.talia.ws.codex.protocol.request.InitializedNotification;
import com.chatbot.talia.ws.codex.protocol.request.ThreadListRequest;
import com.chatbot.talia.ws.codex.protocol.request.ThreadResumeRequest;
import com.chatbot.talia.ws.codex.protocol.request.ThreadStartRequest;
import com.chatbot.talia.ws.codex.protocol.request.ThreadTurnsListRequest;
import com.chatbot.talia.ws.codex.protocol.request.ThreadUnsubscribeRequest;
import com.chatbot.talia.ws.codex.protocol.request.TurnStartRequest;
import com.chatbot.talia.ws.codex.protocol.response.InitializeResponse;
import com.chatbot.talia.ws.codex.protocol.response.JsonRpcSuccessResponse;
import com.chatbot.talia.ws.codex.protocol.response.ThreadListResponse;
import com.chatbot.talia.ws.codex.protocol.response.ThreadResumeResponse;
import com.chatbot.talia.ws.codex.protocol.response.ThreadStartResponse;
import com.chatbot.talia.ws.codex.protocol.response.ThreadTurnsListResponse;
import com.chatbot.talia.ws.codex.protocol.response.ThreadUnsubscribeResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.WebSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

@Component
@Profile("codex")
public class CodexWebSocketClient implements AutoCloseable {
    private final CodexMessageCodec messageCodec;
    private final CodexWebSocketConnection connection;
    private final CodexMessageDispatcher dispatcher;
    private final AtomicLong nextRequestId = new AtomicLong();
    private final AtomicBoolean handshakeStarted = new AtomicBoolean();
    private volatile boolean initialized;

    public CodexWebSocketClient(
            @Value("${talia.codex.url:ws://127.0.0.1:4222}") String serverUrl,
            CodexMessageCodec messageCodec,
            CodexMessageHandlerRegistry handlerRegistry) {
        this.messageCodec = messageCodec;
        this.dispatcher = new CodexMessageDispatcher(messageCodec, handlerRegistry);
        this.connection = new CodexWebSocketConnection(URI.create(serverUrl), dispatcher);
    }

    public CompletableFuture<InitializeResponse> connectAndInitialize(String name, String title, String version) {
        if (!handshakeStarted.compareAndSet(false, true)) {
            return CompletableFuture.failedFuture(new IllegalStateException("Codex initialization was already started"));
        }

        try {
            connection.open();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return CompletableFuture.failedFuture(error);
        } catch (ExecutionException error) {
            connection.abort(error.getCause());
            return CompletableFuture.failedFuture(error.getCause());
        } catch (TimeoutException | RuntimeException error) {
            connection.abort(error);
            return CompletableFuture.failedFuture(error);
        }

        InitializeRequest request = InitializeRequest.create(name, title, version);
        CompletableFuture<InitializeResponse> response = sendRequest(request, InitializeResponse.class);
        return response.thenCompose(this::sendInitialized)
                .thenCombine(response, this::markInitialized)
                .whenComplete(this::abortOnInitializationFailure)
                .copy();
    }

    private CompletableFuture<Void> sendInitialized(InitializeResponse response) {
        return sendEncodedNotification(InitializedNotification.create());
    }

    private InitializeResponse markInitialized(Void sent, InitializeResponse response) {
        Throwable cause = dispatcher.closeCause();
        if (cause != null) {
            throw new CompletionException(cause);
        }
        initialized = true;
        return response;
    }

    private void abortOnInitializationFailure(InitializeResponse response, Throwable error) {
        if (error != null && dispatcher.closeCause() == null) {
            connection.abort(error instanceof CompletionException ? error.getCause() : error);
        }
    }

    public CompletableFuture<WebSocket> ping(String message) {
        return connection.ping(message);
    }

    public CompletableFuture<Void> sendNotification(CodexNotification<?> notification) {
        Throwable failure = checkReady();
        if (failure != null) {
            return CompletableFuture.failedFuture(failure);
        }
        if (CodexMethod.INITIALIZED.value().equals(notification.method())) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("The client sends initialized during the handshake"));
        }
        return sendEncodedNotification(notification);
    }

    private CompletableFuture<Void> sendEncodedNotification(CodexNotification<?> notification) {
        try {
            return connection.send(messageCodec.encode(notification));
        } catch (RuntimeException error) {
            return CompletableFuture.failedFuture(error);
        }
    }

    public <R extends JsonRpcSuccessResponse<?>> CompletableFuture<R> send(
            CodexRequest<?> request, Class<R> responseType) {
        Throwable failure = checkReady();
        if (failure != null) {
            return CompletableFuture.failedFuture(failure);
        }
        if (CodexMethod.INITIALIZE.value().equals(request.method())) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("The client sends initialize during the handshake"));
        }
        return sendRequest(request, responseType);
    }

    private Throwable checkReady() {
        Throwable cause = dispatcher.closeCause();
        if (cause != null) {
            return cause;
        }
        return initialized ? null : new IllegalStateException("Codex session is not initialized");
    }

    /** Streams text on the WebSocket listener thread and returns the server's terminal turn outcome. */
    public CompletableFuture<CodexTurnResult> runTurn(TurnStartRequest request, Consumer<String> onText) {
        Throwable failure = checkReady();
        if (failure != null) {
            return CompletableFuture.failedFuture(failure);
        }

        String threadId = request.params().threadId();
        CodexTurn turn = new CodexTurn(onText);
        if (!dispatcher.registerTurn(threadId, turn)) {
            return CompletableFuture.failedFuture(new IllegalStateException("Already tracking a turn on thread " + threadId));
        }
        sendRequest(request, turn.startRequest());
        return turn.result();
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
            ThreadTurnsListResponse.Result page = send(new ThreadTurnsListRequest(null, params),
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
            ThreadListResponse.Result page = listConversations(Math.min(100, conversationCount - histories.size()), cursor)
                    .get().result();
            for (ThreadListResponse.ThreadInfo thread : page.data()) {
                histories.add(getConversationHistory(thread.id(), messageLimit, order));
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

    public ConversationHistory openConversation(String threadId, int messageLimit, ConversationHistory.Order order)
            throws InterruptedException, ExecutionException {
        Objects.requireNonNull(threadId);
        Objects.requireNonNull(order);
        if (messageLimit < 0) {
            throw new IllegalArgumentException("Message limit cannot be negative");
        }
        ThreadResumeResponse response = send(ThreadResumeRequest.create(threadId), ThreadResumeResponse.class).get();
        return getConversationHistory(response.result().thread().id(), messageLimit, order);
    }

    public CompletableFuture<ThreadStartResponse> startConversation(CodexModel model) {
        return send(ThreadStartRequest.create(model), ThreadStartResponse.class);
    }

    /** Unsubscribes this connection while preserving the conversation and its history. */
    public CompletableFuture<ThreadUnsubscribeResponse> finishConversation(String threadId) {
        Objects.requireNonNull(threadId);
        CodexTurn turn = dispatcher.trackedTurn(threadId);
        CompletableFuture<ThreadUnsubscribeResponse> response = send(
                ThreadUnsubscribeRequest.create(threadId), ThreadUnsubscribeResponse.class);
        return response.whenComplete(new ConversationFinish(threadId, turn, dispatcher)).copy();
    }

    public CompletableFuture<ThreadListResponse> listConversations(int limit) {
        return listConversations(limit, null);
    }

    public CompletableFuture<ThreadListResponse> listConversations(int limit, String cursor) {
        if (limit < 1 || limit > 100) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Conversation page size must be 1 to 100"));
        }
        return send(ThreadListRequest.create(limit, cursor), ThreadListResponse.class);
    }

    /** Lists the request methods declared in Codex's generated ClientRequest.json. */
    public List<String> getAvailableActions(Path clientRequestSchema) throws IOException {
        JsonNode requests = new ObjectMapper().readTree(Files.readString(clientRequestSchema)).path("oneOf");
        if (!requests.isArray() || requests.isEmpty()) {
            throw new IOException("Expected request definitions in " + clientRequestSchema);
        }

        Set<String> methods = new LinkedHashSet<>();
        for (JsonNode request : requests) {
            JsonNode method = request.path("properties").path("method");
            JsonNode name = method.has("const") ? method.path("const") : method.path("enum").path(0);
            if (!name.isString() || name.asString().isBlank()) {
                throw new IOException("Invalid request method definition in " + clientRequestSchema);
            }
            methods.add(name.asString());
        }
        return List.copyOf(methods);
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

        StringBuilder text = new StringBuilder();
        JsonNode content = item.content();
        if (content != null) {
            for (JsonNode input : content) {
                if ("text".equals(input.path("type").asString()) && input.path("text").isString()) {
                    text.append(input.path("text").asString());
                }
            }
        }
        return new ConversationHistory.Message(ConversationHistory.Role.USER, text.toString(), content);
    }

    private record ConversationFinish(String threadId, CodexTurn turn, CodexMessageDispatcher dispatcher)
            implements BiConsumer<ThreadUnsubscribeResponse, Throwable> {
        @Override
        public void accept(ThreadUnsubscribeResponse response, Throwable error) {
            if (error == null) {
                dispatcher.stopTrackingTurn(threadId, turn);
            }
        }
    }

    private <R extends JsonRpcSuccessResponse<?>> CompletableFuture<R> sendRequest(
            CodexRequest<?> request, Class<R> responseType) {
        PendingRequest<R> pending = new PendingRequest<>(Objects.requireNonNull(responseType), new CompletableFuture<>());
        sendRequest(request, pending);
        return pending.future();
    }

    private void sendRequest(CodexRequest<?> request, PendingRequest<?> pending) {
        long id;
        String json;
        try {
            id = nextRequestId.getAndUpdate(Math::incrementExact);
            CodexRequest<?> outbound = request.withId(id);
            if (!Objects.equals(outbound.id(), id)) {
                throw new IllegalArgumentException("Request must use the assigned ID: " + id);
            }
            json = messageCodec.encode(outbound);
        } catch (RuntimeException error) {
            pending.future().completeExceptionally(error);
            return;
        }

        dispatcher.register(id, pending);
        connection.send(json, pending.future()::isDone).whenComplete(pending::onSendCompleted);
    }

    @Override
    public void close() {
        connection.close();
    }

    public CompletableFuture<Void> closeAsync() {
        return connection.closeAsync();
    }
}
