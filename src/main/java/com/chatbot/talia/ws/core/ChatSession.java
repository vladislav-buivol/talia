package com.chatbot.talia.ws.core;

import com.chatbot.talia.ws.core.protocol.ChatAction;
import com.chatbot.talia.ws.core.protocol.response.ChatMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;

/** Owns writer assignment and in-flight turns for one conversation. */
final class ChatSession {
    private static final Logger log = LoggerFactory.getLogger(ChatSession.class);
    private final String threadId;
    private final ObjectMapper mapper;
    private final ChatBackend.Conversation conversation;
    private final Consumer<ChatSession> onIdle;
    private final LinkedHashMap<String, ChatPeer> peers = new LinkedHashMap<>();
    private String writerId;
    private String activeTurn;
    private String model;

    ChatSession(ObjectMapper mapper, ChatBackend.Conversation conversation, String model, Consumer<ChatSession> onIdle) {
        this.threadId = conversation.threadId();
        this.mapper = mapper;
        this.conversation = conversation;
        this.model = model;
        this.onIdle = onIdle;
    }

    String threadId() {
        return threadId;
    }

    synchronized boolean hasWriter() {
        return writerId != null;
    }

    synchronized boolean join(ChatPeer peer, boolean asWriter) {
        if ((asWriter && writerId != null) || !peer.attach(this)) {
            return false;
        }
        peers.put(peer.id(), peer);
        if (asWriter) {
            writerId = peer.id();
        }
        send(peer, event("session.joined")
                .put("clientId", peer.id())
                .put("writerId", writerId)
                .put("turnId", activeTurn));
        broadcastSessionState(peer);
        return true;
    }

    private void broadcastSessionState(ChatPeer joiningPeer) {
        for (ChatPeer peer : peers.values()) {
            if (peer != joiningPeer) {
                send(peer, event("session.updated").put("writerId", writerId).put("joinedClientId", joiningPeer.id()));
            }
        }
    }

    synchronized void claimWriter(ChatPeer peer) {
        if (!peers.containsKey(peer.id()) || writerId != null) {
            error(peer, "writer_unavailable", "This chat already has a writer");
            return;
        }
        writerId = peer.id();
        broadcast(event("writer.changed").put("writerId", writerId));
    }

    synchronized void changeModel(ChatPeer peer, String model) {
        if (canWrite(peer)) {
            this.model = model;
            broadcast(event("model.changed").put("model", model));
        }
    }

    synchronized List<ChatPeer> finish(ChatPeer peer) {
        if (!canWrite(peer)) {
            return null;
        }
        return end();
    }

    synchronized List<ChatPeer> delete(ChatPeer peer) throws InterruptedException, ExecutionException {
        if (!canWrite(peer)) {
            return null;
        }
        conversation.delete();
        return end();
    }

    private List<ChatPeer> end() {
        List<ChatPeer> participants = List.copyOf(peers.values());
        peers.clear();
        writerId = null;
        close();
        return participants;
    }

    private boolean canWrite(ChatPeer peer) {
        if (!peer.id().equals(writerId)) {
            error(peer, "read_only", "Only the current writer can perform this action");
        } else if (activeTurn != null) {
            error(peer, "turn_in_progress", "Wait for the active turn to finish");
        } else {
            return true;
        }
        return false;
    }

    void leave(ChatPeer peer) {
        synchronized (this) {
            if (peers.remove(peer.id(), peer)) {
                if (peer.id().equals(writerId)) {
                    writerId = peers.isEmpty() ? null : peers.sequencedKeySet().getFirst();
                    broadcast(event("writer.changed").put("writerId", writerId));
                } else {
                    broadcast(event("session.updated").put("writerId", writerId).put("leftClientId", peer.id()));
                }
            }
        }
        onIdle.accept(this);
    }

    synchronized void transfer(ChatPeer peer, String nextWriterId) {
        if (!peer.id().equals(writerId)) {
            error(peer, "read_only", "Only the current writer can transfer access");
        } else if (!peers.containsKey(nextWriterId)) {
            error(peer, "unknown_client", "The target client must be connected to this thread");
        } else {
            writerId = nextWriterId;
            broadcast(event("writer.changed").put("writerId", writerId));
        }
    }

    void history(ChatPeer peer, int limit) {
        if (limit < 1 || limit > 100) {
            error(peer, "invalid_limit", "History limit must be an integer from 1 to 100");
            return;
        }
        try {
            List<ChatMessage> messages = conversation.history(limit);
            synchronized (this) {
                if (peers.containsKey(peer.id())) {
                    ObjectNode response = event("history");
                    response.set("messages", mapper.valueToTree(messages));
                    send(peer, response);
                }
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            error(peer, "history_failed", "History request interrupted");
        } catch (ExecutionException | RuntimeException error) {
            log.debug("Could not read chat history for {}", threadId, error);
            error(peer, "history_failed", "Could not read conversation history");
        }
    }

    void models(ChatPeer peer, List<String> models) {
        ObjectNode response = event("models");
        response.set("models", mapper.valueToTree(models));
        send(peer, response);
    }

    synchronized void write(ChatPeer peer, String text) {
        if (!canWrite(peer)) {
            return;
        }
        if (text.isBlank()) {
            error(peer, "invalid_text", "Message text is required");
        } else {
            String turnId = UUID.randomUUID().toString();
            activeTurn = turnId;
            broadcast(event("user.text").put("turnId", turnId).put("text", text));
            String selectedModel = model;
            Thread.startVirtualThread(() -> runTurn(turnId, text, selectedModel));
        }
    }

    private void runTurn(String turnId, String text, String selectedModel) {
        try {
            Consumer<String> stream = delta -> broadcast(event("assistant.text.delta").put("turnId", turnId).put("text", delta));
            ChatBackend.TurnResult result = conversation.runTurn(text, selectedModel, stream);
            completeTurn(event("assistant.text.done").put("turnId", turnId)
                    .put("codexTurnId", result.turnId()).put("status", result.status())
                    .put("errorMessage", result.errorMessage()));
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            failTurn(turnId, error);
        } catch (ExecutionException | RuntimeException error) {
            failTurn(turnId, error);
        }
    }

    private void failTurn(String turnId, Exception error) {
        log.debug("Chat turn failed for {}", threadId, error);
        completeTurn(event("error").put("turnId", turnId).put("code", "turn_failed")
                .put("message", "Could not complete the chat turn"));
    }

    private void completeTurn(ObjectNode response) {
        synchronized (this) {
            activeTurn = null;
            broadcast(response);
        }
        onIdle.accept(this);
    }

    synchronized boolean retireIfIdle() {
        if (!peers.isEmpty() || activeTurn != null) {
            return false;
        }
        close();
        return true;
    }

    synchronized void close() {
        conversation.close();
    }

    void error(ChatPeer peer, String code, String message) {
        send(peer, event("error").put("code", code).put("message", message));
    }

    private ObjectNode event(String type) {
        return mapper.createObjectNode().put("type", type).put("threadId", threadId);
    }

    private synchronized void send(ChatPeer peer, ObjectNode event) {
        if (!peers.containsKey(peer.id())) {
            return;
        }
        String type = event.remove("type").asString();
        send(peer, type, event);
    }

    private synchronized void broadcast(ObjectNode event) {
        String type = event.remove("type").asString();
        for (ChatPeer peer : peers.values()) {
            send(peer, type, event);
        }
    }

    private void send(ChatPeer peer, String type, ObjectNode data) {
        List<ChatAction> actions = new ArrayList<>(List.of(ChatAction.GET_HISTORY, ChatAction.CLOSE_CHAT, ChatAction.GET_MODELS));
        if (writerId == null) {
            actions.add(ChatAction.CONNECT_AS_WRITER);
        } else if (peer.id().equals(writerId)) {
            if (activeTurn == null) {
                actions.addAll(List.of(ChatAction.SEND_MESSAGE, ChatAction.CHANGE_MODEL, ChatAction.FINISH_CHAT,
                        ChatAction.DELETE_CHAT, ChatAction.CLOSE_CHAT_AND_DELETE, ChatAction.FINISH_CHAT_AND_DELETE));
            }
            if (peers.size() > 1) {
                actions.add(ChatAction.TRANSFER_WRITER);
            }
        }
        peer.send(type, actions, data);
    }
}
