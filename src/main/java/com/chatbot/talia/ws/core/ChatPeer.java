package com.chatbot.talia.ws.core;

import com.chatbot.talia.ws.core.protocol.ChatAction;
import com.chatbot.talia.ws.core.protocol.ChatResponse;
import com.chatbot.talia.ws.core.protocol.response.ChatEvent;
import java.util.List;
import java.util.function.Consumer;

final class ChatPeer {
    private final String id;
    private final Consumer<ChatResponse> responses;
    private ChatSession chat;
    private String selectedThreadId;
    private boolean disconnected;

    ChatPeer(String id, Consumer<ChatResponse> responses) {
        this.id = id;
        this.responses = responses;
    }

    String id() {
        return id;
    }

    void send(String type, List<ChatAction> actions, Object data) {
        responses.accept(new ChatEvent(type, actions, data));
    }

    synchronized ChatSession chat() {
        return chat;
    }

    synchronized boolean attach(ChatSession chat) {
        if (!isConnected() || this.chat != null) {
            return false;
        }
        this.chat = chat;
        selectedThreadId = null;
        return true;
    }

    synchronized ChatSession detach() {
        ChatSession previous = chat;
        chat = null;
        return previous;
    }

    synchronized boolean detach(ChatSession expected) {
        if (chat != expected) {
            return false;
        }
        detach();
        return true;
    }

    synchronized boolean isConnected() {
        return !disconnected;
    }

    synchronized void disconnect() {
        disconnected = true;
    }

    synchronized String selectedThreadId() {
        return selectedThreadId;
    }

    synchronized void select(String threadId) {
        selectedThreadId = threadId;
    }

}
