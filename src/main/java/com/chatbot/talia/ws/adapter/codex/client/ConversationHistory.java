package com.chatbot.talia.ws.adapter.codex.client;

import com.chatbot.talia.ws.adapter.codex.protocol.response.ThreadTurnsListResponse;

import java.util.List;

public record ConversationHistory(String threadId, List<Message> messages) {
    public ConversationHistory {
        messages = List.copyOf(messages);
    }

    public enum Order {
        OLDEST_FIRST,
        NEWEST_FIRST
    }

    public enum Role {
        USER,
        ASSISTANT
    }

    /** Retains structured user input without exposing Jackson to consumers. */
    public record Message(Role role, String text, List<ThreadTurnsListResponse.UserInput> userInput) {
    }
}
