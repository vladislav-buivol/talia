package com.chatbot.talia.ws.core;

import com.chatbot.talia.ws.core.protocol.ChatAction;
import com.chatbot.talia.ws.core.protocol.ChatRequest;
import com.chatbot.talia.ws.core.protocol.ChatResponse;
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
    private static final List<ChatAction> MENU = List.of(START_NEW_DIALOG, GET_DIALOGS, CONTINUE_DIALOG);
    private final ObjectMapper mapper;
    private final ChatBackend backend;
    private final ConcurrentHashMap<String, ChatSession> chats = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ChatPeer> peers = new ConcurrentHashMap<>();

    public ChatService(ObjectMapper mapper, ChatBackend backend) {
        this.mapper = mapper;
        this.backend = backend;
    }

    public void connect(String clientId, Consumer<ChatResponse> responses) {
        ChatPeer peer = new ChatPeer(clientId, responses);
        peers.put(clientId, peer);
        peer.send("connected", MENU, null);
    }

    public void handle(String clientId, ChatRequest.Command request) {
        ChatPeer peer = peers.get(clientId);
        if (peer == null) {
            return;
        }
        if (request == null || request.action() == null) {
            error(peer, "invalid_request", "Action is required");
            return;
        }
        String model = request.model();
        if (model != null && !backend.supportsModel(model)) {
            error(peer, "invalid_request", "Expected a JSON request with a valid action");
            return;
        }

        ChatSession chat = peer.chat();
        if (chat == null) {
            handleMenu(peer, request, model);
        } else {
            switch (request.action()) {
                case GET_HISTORY -> chat.history(peer, limit(request));
                case SEND_MESSAGE -> chat.write(peer, request.text() == null ? "" : request.text());
                case CHANGE_MODEL -> {
                    if (model == null) {
                        chat.error(peer, "invalid_model", "Model is required");
                    } else {
                        chat.changeModel(peer, model);
                    }
                }
                case CLOSE_CHAT -> {
                    if (peer.detach(chat)) {
                        chat.leave(peer);
                        peer.send("chat.closed", MENU, Map.of("threadId", chat.threadId()));
                    }
                }
                case FINISH_CHAT -> finish(peer, chat);
                case CONNECT_AS_WRITER -> chat.claimWriter(peer);
                case TRANSFER_WRITER -> chat.transfer(peer, request.clientId());
                default -> chat.error(peer, "action_unavailable", "Close the current chat to return to the menu");
            }
        }
    }

    private void handleMenu(ChatPeer peer, ChatRequest.Command request, String model) {
        try {
            switch (request.action()) {
                case START_NEW_DIALOG -> start(peer, model);
                case GET_DIALOGS -> list(peer, request);
                case CONTINUE_DIALOG -> select(peer, request.threadId());
                case CONNECT_AS_READER, CONNECT_AS_WRITER -> {
                    String threadId = request.threadId() == null ? peer.selectedThreadId() : request.threadId();
                    if (validThreadId(threadId)) {
                        join(peer, threadId, request.action() == CONNECT_AS_WRITER);
                    } else {
                        error(peer, "invalid_thread", "Select a dialog or provide its threadId");
                    }
                }
                default -> error(peer, "action_unavailable", "Select a chat first");
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            error(peer, "codex_unavailable", "Chat request interrupted");
        } catch (ExecutionException | RuntimeException failure) {
            log.debug("Chat action {} failed", request.action(), failure);
            error(peer, "codex_unavailable", "Could not complete the chat request");
        }
    }

    private void start(ChatPeer peer, String model) throws InterruptedException, ExecutionException {
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

    private void list(ChatPeer peer, ChatRequest.Command request) throws InterruptedException, ExecutionException {
        int limit = limit(request);
        if (limit < 1 || limit > 100) {
            error(peer, "invalid_limit", "Limit must be an integer from 1 to 100");
            return;
        }
        ChatBackend.DialogPage page = backend.list(limit, request.cursor());
        List<ChatResponse.Dialog> dialogs = new ArrayList<>();
        for (ChatBackend.Dialog dialog : page.dialogs()) {
            dialogs.add(new ChatResponse.Dialog(dialog.threadId(), dialog.name(), dialog.preview(), roles(dialog.threadId())));
        }
        peer.send("dialogs", menu(peer), new ChatResponse.DialogPage(dialogs, page.nextCursor()));
    }

    private List<ChatAction> roles(String threadId) {
        ChatSession chat = chats.get(threadId);
        return chat != null && chat.hasWriter() ? List.of(CONNECT_AS_READER) : List.of(CONNECT_AS_READER, CONNECT_AS_WRITER);
    }

    private List<ChatAction> menu(ChatPeer peer) {
        String threadId = peer.selectedThreadId();
        if (threadId == null) {
            return MENU;
        }
        List<ChatAction> actions = new ArrayList<>(MENU);
        actions.addAll(roles(threadId));
        return actions;
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
            for (ChatPeer participant : participants) {
                if (participant.detach(chat)) {
                    participant.send("chat.finished", MENU, Map.of("threadId", id));
                }
            }
            return null;
        });
    }

    private ChatSession newChat(String threadId) {
        return new ChatSession(mapper, backend.open(threadId), null, this::removeIfIdle);
    }

    private static boolean validThreadId(String threadId) {
        return threadId != null && !threadId.isBlank() && threadId.length() <= 128;
    }

    private static int limit(ChatRequest.Command request) {
        return request.limit() == null ? 50 : request.limit();
    }

    private void error(ChatPeer peer, String code, String message) {
        ChatSession chat = peer.chat();
        if (chat == null) {
            peer.send("error", menu(peer), Map.of("code", code, "message", message));
        } else {
            chat.error(peer, code, message);
        }
    }

    public void invalidRequest(String clientId) {
        ChatPeer peer = peers.get(clientId);
        if (peer != null) {
            error(peer, "invalid_request", "Expected a JSON request with a valid action");
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
