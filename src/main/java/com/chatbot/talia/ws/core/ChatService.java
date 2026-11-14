package com.chatbot.talia.ws.core;

import com.chatbot.talia.ws.core.protocol.ChatAction;
import com.chatbot.talia.ws.core.protocol.ChatRequest;
import com.chatbot.talia.ws.core.protocol.ChatRequest.*;
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

    public void handle(String clientId, ChatRequest request) {
        ChatPeer peer = peers.get(clientId);
        if (peer == null) {
            return;
        }
        ChatSession chat = peer.chat();
        if (chat == null) {
            handleMenu(peer, request);
        } else {
            switch (request) {
                case GetHistoryRequest history -> chat.history(peer, history.limit() == null ? 50 : history.limit());
                case SendMessageRequest message -> chat.write(peer, message.text() == null ? "" : message.text());
                case ChangeModelRequest change -> {
                    if (change.model() == null || !backend.supportsModel(change.model())) {
                        chat.error(peer, "invalid_model", "A supported model is required");
                    } else {
                        chat.changeModel(peer, change.model());
                    }
                }
                case CloseChatRequest ignored -> {
                    if (peer.detach(chat)) {
                        chat.leave(peer);
                        peer.send("chat.closed", MENU, Map.of("threadId", chat.threadId()));
                    }
                }
                case FinishChatRequest ignored -> finish(peer, chat);
                case CloseChatAndDeleteRequest ignored -> delete(peer, chat.threadId());
                case FinishChatAndDeleteRequest ignored -> delete(peer, chat.threadId());
                case DeleteChatRequest deletion -> {
                    if (deletion.threadId() == null || chat.threadId().equals(deletion.threadId())) {
                        delete(peer, chat.threadId());
                    } else {
                        chat.error(peer, "invalid_thread", "Close the current chat to delete another dialog");
                    }
                }
                case ConnectAsWriterRequest ignored -> chat.claimWriter(peer);
                case TransferWriterRequest transfer -> chat.transfer(peer, transfer.clientId());
                default -> chat.error(peer, "action_unavailable", "Close the current chat to return to the menu");
            }
        }
    }

    private void handleMenu(ChatPeer peer, ChatRequest request) {
        try {
            switch (request) {
                case StartNewDialogRequest start -> start(peer, start.model());
                case GetDialogsRequest dialogs -> list(peer, dialogs);
                case ContinueDialogRequest dialog -> select(peer, dialog.threadId());
                case DeleteChatRequest deletion -> delete(peer,
                        deletion.threadId() == null ? peer.selectedThreadId() : deletion.threadId());
                case ConnectAsReaderRequest reader -> joinSelectedDialog(peer, reader.threadId(), false);
                case ConnectAsWriterRequest writer -> joinSelectedDialog(peer, writer.threadId(), true);
                default -> error(peer, "action_unavailable", "Select a chat first");
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            error(peer, "codex_unavailable", "Chat request interrupted");
        } catch (ExecutionException | RuntimeException failure) {
            log.debug("Chat request {} failed", request.getClass().getSimpleName(), failure);
            error(peer, "codex_unavailable", "Could not complete the chat request");
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
        List<ChatResponse.Dialog> dialogs = new ArrayList<>();
        for (ChatBackend.Dialog dialog : page.dialogs()) {
            dialogs.add(new ChatResponse.Dialog(dialog.threadId(), dialog.name(), dialog.preview(), dialogActions(dialog.threadId())));
        }
        peer.send("dialogs", menu(peer), new ChatResponse.DialogPage(dialogs, page.nextCursor()));
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
