package com.chatbot.talia.ws.core;

import com.chatbot.talia.ws.core.protocol.ChatAction;
import com.chatbot.talia.ws.core.protocol.ChatRequest;
import com.chatbot.talia.ws.core.protocol.ChatResponse;
import com.chatbot.talia.ws.core.protocol.request.CancelTurnRequest;
import com.chatbot.talia.ws.core.protocol.request.ChangeModelRequest;
import com.chatbot.talia.ws.core.protocol.request.CloseChatAndDeleteRequest;
import com.chatbot.talia.ws.core.protocol.request.CloseChatRequest;
import com.chatbot.talia.ws.core.protocol.request.ConnectAsReaderRequest;
import com.chatbot.talia.ws.core.protocol.request.ConnectAsWriterRequest;
import com.chatbot.talia.ws.core.protocol.request.ContinueDialogRequest;
import com.chatbot.talia.ws.core.protocol.request.DeleteChatRequest;
import com.chatbot.talia.ws.core.protocol.request.FinishChatAndDeleteRequest;
import com.chatbot.talia.ws.core.protocol.request.FinishChatRequest;
import com.chatbot.talia.ws.core.protocol.request.GetDialogsRequest;
import com.chatbot.talia.ws.core.protocol.request.GetHistoryRequest;
import com.chatbot.talia.ws.core.protocol.request.GetModelsRequest;
import com.chatbot.talia.ws.core.protocol.request.ResolveApprovalRequest;
import com.chatbot.talia.ws.core.protocol.request.SendMessageRequest;
import com.chatbot.talia.ws.core.protocol.request.StartNewDialogRequest;
import com.chatbot.talia.ws.core.protocol.request.TransferWriterRequest;
import com.chatbot.talia.ws.core.protocol.response.ChatDialog;
import com.chatbot.talia.ws.core.protocol.response.ChatDialogPage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;

import static com.chatbot.talia.ws.core.protocol.ChatAction.*;

public final class ChatService implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(ChatService.class);
    private static final List<ChatAction> MENU = List.of(START_NEW_DIALOG, GET_DIALOGS, CONTINUE_DIALOG, GET_MODELS);
    private final ObjectMapper mapper;
    private final ChatBackend backend;
    private final ConcurrentHashMap<String, ChatSession> chats = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ChatPeer> peers = new ConcurrentHashMap<>();
    private final Map<Class<? extends ChatRequest>, RegisteredHandler<?>> handlers;

    public ChatService(ObjectMapper mapper, ChatBackend backend) {
        this.mapper = mapper;
        this.backend = backend;
        this.handlers = Map.ofEntries(
                register(StartNewDialogRequest.class, START_NEW_DIALOG, HandlerScope.MENU, this::handleStartNewDialog),
                register(GetDialogsRequest.class, GET_DIALOGS, HandlerScope.MENU, this::handleGetDialogs),
                register(ContinueDialogRequest.class, CONTINUE_DIALOG, HandlerScope.MENU, this::handleContinueDialog),
                register(GetModelsRequest.class, GET_MODELS, HandlerScope.BOTH, this::handleGetModels),
                register(GetHistoryRequest.class, GET_HISTORY, HandlerScope.CHAT, this::handleGetHistory),
                register(SendMessageRequest.class, SEND_MESSAGE, HandlerScope.CHAT, this::handleSendMessage),
                register(CancelTurnRequest.class, CANCEL_TURN, HandlerScope.CHAT, this::handleCancelTurn),
                register(ResolveApprovalRequest.class, RESOLVE_APPROVAL, HandlerScope.CHAT, this::handleResolveApproval),
                register(ChangeModelRequest.class, CHANGE_MODEL, HandlerScope.CHAT, this::handleChangeModel),
                register(CloseChatRequest.class, CLOSE_CHAT, HandlerScope.CHAT, this::handleCloseChat),
                register(FinishChatRequest.class, FINISH_CHAT, HandlerScope.CHAT, this::handleFinishChat),
                register(CloseChatAndDeleteRequest.class, CLOSE_CHAT_AND_DELETE, HandlerScope.CHAT,
                        this::handleCloseChatAndDelete),
                register(FinishChatAndDeleteRequest.class, FINISH_CHAT_AND_DELETE, HandlerScope.CHAT,
                        this::handleFinishChatAndDelete),
                register(DeleteChatRequest.class, DELETE_CHAT, HandlerScope.BOTH, this::handleDeleteChat),
                register(ConnectAsReaderRequest.class, CONNECT_AS_READER, HandlerScope.MENU,
                        this::handleConnectAsReader),
                register(ConnectAsWriterRequest.class, CONNECT_AS_WRITER, HandlerScope.BOTH,
                        this::handleConnectAsWriter),
                register(TransferWriterRequest.class, TRANSFER_WRITER, HandlerScope.CHAT, this::handleTransferWriter));
    }

    public void connect(String clientId, Consumer<ChatResponse> responses) {
        ChatPeer peer = new ChatPeer(clientId, responses);
        peers.put(clientId, peer);
        peer.send("connected", MENU, null);
    }

    public void handle(String clientId, ChatRequest request) {
        ChatPeer peer = peers.get(clientId);
        if (peer == null) {
            return;
        }

        if (request == null) {
            error(peer, "invalid_request", "Chat request is required");
            return;
        }

        ChatSession chat = peer.chat();
        RegisteredHandler<?> handler = handlers.get(request.getClass());
        if (handler == null || !handler.scope().supports(chat)) {
            error(peer, "action_unavailable", chat == null
                    ? "Select a chat first"
                    : "Close the current chat to return to the menu");
            return;
        }

        log.info("Chat handler invoked: action={}, handler={}, context={}",
                handler.action().value(),
                handler.requestType().getSimpleName(),
                chat == null ? "menu" : "chat");

        try {
            handler.execute(peer, chat, request);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            log.warn("Chat handler interrupted: action={}", handler.action().value(), failure);
            error(peer, "codex_unavailable", "Chat request interrupted");
        } catch (ExecutionException | RuntimeException failure) {
            log.warn("Chat handler failed: action={}", handler.action().value(), failure);
            error(peer, "codex_unavailable", "Could not complete the chat request");
        }
    }

    private void handleStartNewDialog(ChatPeer peer, ChatSession chat, StartNewDialogRequest request)
            throws InterruptedException, ExecutionException {
        start(peer, request.model());
    }

    private void handleGetDialogs(ChatPeer peer, ChatSession chat, GetDialogsRequest request)
            throws InterruptedException, ExecutionException {
        list(peer, request);
    }

    private void handleContinueDialog(ChatPeer peer, ChatSession chat, ContinueDialogRequest request) {
        select(peer, request.threadId());
    }

    private void handleGetModels(ChatPeer peer, ChatSession chat, GetModelsRequest request) {
        if (chat == null) {
            peer.send("models", menu(peer), Map.of("models", backend.models()));
        } else {
            chat.models(peer, backend.models());
        }
    }

    private void handleGetHistory(ChatPeer peer, ChatSession chat, GetHistoryRequest request) {
        chat.history(peer, request.limit() == null ? 50 : request.limit());
    }

    private void handleSendMessage(ChatPeer peer, ChatSession chat, SendMessageRequest request) {
        chat.write(peer, request.text() == null ? "" : request.text());
    }

    private void handleCancelTurn(ChatPeer peer, ChatSession chat, CancelTurnRequest request) {
        chat.cancel(peer);
    }

    private void handleResolveApproval(ChatPeer peer, ChatSession chat, ResolveApprovalRequest request) {
        chat.resolveApproval(peer, request.requestId(), request.decision());
    }

    private void handleChangeModel(ChatPeer peer, ChatSession chat, ChangeModelRequest request) {
        if (request.model() == null || !backend.supportsModel(request.model())) {
            chat.error(peer, "invalid_model", "A supported model is required");
            return;
        }
        chat.changeModel(peer, request.model());
    }

    private void handleCloseChat(ChatPeer peer, ChatSession chat, CloseChatRequest request) {
        if (peer.detach(chat)) {
            chat.leave(peer);
            peer.send("chat.closed", MENU, Map.of("threadId", chat.threadId()));
        }
    }

    private void handleFinishChat(ChatPeer peer, ChatSession chat, FinishChatRequest request) {
        finish(peer, chat);
    }

    private void handleCloseChatAndDelete(ChatPeer peer, ChatSession chat, CloseChatAndDeleteRequest request) {
        delete(peer, chat.threadId());
    }

    private void handleFinishChatAndDelete(ChatPeer peer, ChatSession chat, FinishChatAndDeleteRequest request) {
        delete(peer, chat.threadId());
    }

    private void handleDeleteChat(ChatPeer peer, ChatSession chat, DeleteChatRequest request) {
        if (chat == null) {
            delete(peer, request.threadId() == null ? peer.selectedThreadId() : request.threadId());
        } else if (request.threadId() == null || chat.threadId().equals(request.threadId())) {
            delete(peer, chat.threadId());
        } else {
            chat.error(peer, "invalid_thread", "Close the current chat to delete another dialog");
        }
    }

    private void handleConnectAsReader(ChatPeer peer, ChatSession chat, ConnectAsReaderRequest request) {
        joinSelectedDialog(peer, request.threadId(), false);
    }

    private void handleConnectAsWriter(ChatPeer peer, ChatSession chat, ConnectAsWriterRequest request) {
        if (chat == null) {
            joinSelectedDialog(peer, request.threadId(), true);
        } else {
            chat.claimWriter(peer);
        }
    }

    private void handleTransferWriter(ChatPeer peer, ChatSession chat, TransferWriterRequest request) {
        chat.transfer(peer, request.clientId());
    }

    private static <T extends ChatRequest> Map.Entry<Class<? extends ChatRequest>, RegisteredHandler<?>>
    register(Class<T> requestType, ChatAction action, HandlerScope scope, ChatRequestHandler<T> handler) {
        return Map.entry(requestType, new RegisteredHandler<>(requestType, action, scope, handler));
    }

    private enum HandlerScope {
        MENU,
        CHAT,
        BOTH;

        boolean supports(ChatSession chat) {
            return this == BOTH || this == (chat == null ? MENU : CHAT);
        }
    }

    @FunctionalInterface
    private interface ChatRequestHandler<T extends ChatRequest> {
        void handle(ChatPeer peer, ChatSession chat, T request) throws InterruptedException, ExecutionException;
    }

    private record RegisteredHandler<T extends ChatRequest>(
            Class<T> requestType,
            ChatAction action,
            HandlerScope scope,
            ChatRequestHandler<T> handler) {

        void execute(ChatPeer peer, ChatSession chat, ChatRequest request)
                throws InterruptedException, ExecutionException {
            handler.handle(peer, chat, requestType.cast(request));
        }
    }

    private void start(ChatPeer peer, String model) throws InterruptedException, ExecutionException {
        if (model != null && !backend.supportsModel(model)) {
            error(peer, "invalid_model", "A supported model is required");
            return;
        }
        ChatBackend.Conversation conversation = backend.start(model);
        boolean attached = false;
        try {
            if (!peer.isConnected()) {
                return;
            }
            String threadId = conversation.threadId();
            ChatSession started = new ChatSession(mapper, conversation, model, this::removeIfIdle);
            ChatSession registered = chats.compute(threadId, (id, existing) -> {
                if (existing == null && started.join(peer, true)) {
                    return started;
                }
                return existing;
            });
            attached = registered == started;
            if (!attached && peer.isConnected()) {
                error(peer, "action_unavailable", "Could not join the new chat");
            }
        } finally {
            if (!attached) {
                conversation.close();
            }
        }
    }

    private void select(ChatPeer peer, String threadId) {
        if (!validThreadId(threadId)) {
            error(peer, "invalid_thread", "Provide a threadId returned by getDialogs");
            return;
        }
        peer.select(threadId);
        peer.send("dialog.selected", menu(peer), Map.of("threadId", threadId));
    }

    private void list(ChatPeer peer, GetDialogsRequest request) throws InterruptedException, ExecutionException {
        Integer limit = request.limit();
        if (limit == null || limit < 1 || limit > 100) {
            error(peer, "invalid_limit", "Limit must be an integer from 1 to 100");
            return;
        }
        ChatBackend.DialogPage page = backend.list(limit, request.cursor());
        List<ChatDialog> dialogs = new ArrayList<>();
        for (ChatBackend.Dialog dialog : page.dialogs()) {
            dialogs.add(new ChatDialog(dialog.threadId(), dialog.name(), dialog.preview(), dialogActions(dialog.threadId())));
        }
        peer.send("dialogs", menu(peer), new ChatDialogPage(dialogs, page.nextCursor()));
    }

    private List<ChatAction> dialogActions(String threadId) {
        ChatSession chat = chats.get(threadId);
        if (chat == null) {
            return List.of(CONNECT_AS_READER, CONNECT_AS_WRITER, DELETE_CHAT);
        }
        return chat.hasWriter() ? List.of(CONNECT_AS_READER) : List.of(CONNECT_AS_READER, CONNECT_AS_WRITER);
    }

    private List<ChatAction> menu(ChatPeer peer) {
        String threadId = peer.selectedThreadId();
        if (threadId == null) {
            return MENU;
        }
        List<ChatAction> actions = new ArrayList<>(MENU);
        actions.addAll(dialogActions(threadId));
        return actions;
    }

    private void joinSelectedDialog(ChatPeer peer, String requestedThreadId, boolean asWriter) {
        String threadId = requestedThreadId == null ? peer.selectedThreadId() : requestedThreadId;
        if (validThreadId(threadId)) {
            join(peer, threadId, asWriter);
        } else {
            error(peer, "invalid_thread", "Select a dialog or provide its threadId");
        }
    }

    private void join(ChatPeer peer, String threadId, boolean asWriter) {
        chats.compute(threadId, (id, existing) -> {
            ChatSession chat = existing == null ? newChat(id) : existing;
            if (chat.join(peer, asWriter)) {
                return chat;
            }
            if (existing == null) {
                chat.close();
            }
            return existing;
        });
        if (peer.chat() == null && peer.isConnected()) {
            peer.select(threadId);
            error(peer, "writer_unavailable", "This chat already has a writer");
        }
    }

    private void finish(ChatPeer writer, ChatSession chat) {
        chats.computeIfPresent(chat.threadId(), (id, current) -> {
            if (current != chat) {
                return current;
            }
            List<ChatPeer> participants = chat.finish(writer);
            if (participants == null) {
                return current;
            }
            returnToMenu(chat, participants, "chat.finished");
            return null;
        });
    }

    private void delete(ChatPeer peer, String threadId) {
        if (!validThreadId(threadId)) {
            error(peer, "invalid_thread", "Select a dialog or provide its threadId");
            return;
        }
        chats.compute(threadId, (id, current) -> {
            try {
                if (current == null) {
                    if (peer.chat() != null) {
                        return null;
                    }
                    try (ChatBackend.Conversation conversation = backend.open(id)) {
                        conversation.delete();
                    }
                    if (id.equals(peer.selectedThreadId())) {
                        peer.select(null);
                    }
                    peer.send("chat.deleted", menu(peer), Map.of("threadId", id));
                } else if (peer.chat() == current) {
                    List<ChatPeer> participants = current.delete(peer);
                    if (participants == null) {
                        return current;
                    }
                    returnToMenu(current, participants, "chat.deleted");
                } else {
                    error(peer, "action_unavailable", "Only the current writer can delete an open chat");
                    return current;
                }
                return null;
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                error(peer, "delete_failed", "Chat deletion interrupted");
                return current;
            } catch (ExecutionException | RuntimeException failure) {
                log.debug("Could not delete chat {}", id, failure);
                error(peer, "delete_failed", "Could not delete the chat");
                return current;
            }
        });
    }

    private void returnToMenu(ChatSession chat, List<ChatPeer> participants, String event) {
        for (ChatPeer participant : participants) {
            if (participant.detach(chat)) {
                participant.send(event, MENU, Map.of("threadId", chat.threadId()));
            }
        }
    }

    private ChatSession newChat(String threadId) {
        return new ChatSession(mapper, backend.open(threadId), null, this::removeIfIdle);
    }

    private static boolean validThreadId(String threadId) {
        return threadId != null && !threadId.isBlank() && threadId.length() <= 128;
    }

    private void error(ChatPeer peer, String code, String message) {
        ChatSession chat = peer.chat();
        if (chat == null) {
            peer.send("error", menu(peer), Map.of("code", code, "message", message));
        } else {
            chat.error(peer, code, message);
        }
    }

    public void invalidRequest(String clientId, String message) {
        ChatPeer peer = peers.get(clientId);
        if (peer != null) {
            error(peer, "invalid_request", message);
        }
    }

    @Override
    public void close() {
        for (ChatPeer peer : peers.values()) {
            peer.disconnect();
        }
        for (ChatSession chat : chats.values()) {
            chat.close();
        }
    }

    public void disconnect(String clientId) {
        ChatPeer peer = peers.remove(clientId);
        if (peer != null) {
            peer.disconnect();
            ChatSession chat = peer.detach();
            if (chat != null) {
                chat.leave(peer);
            }
        }
    }

    private void removeIfIdle(ChatSession chat) {
        chats.computeIfPresent(chat.threadId(), (id, current) ->
                current == chat && chat.retireIfIdle() ? null : current);
    }
}
