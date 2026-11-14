package com.chatbot.talia.ws.adapter.codex.client;

import tools.jackson.databind.JsonNode;

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

    /** User messages also retain their structured Codex input, including attachments. */
    public record Message(Role role, String text, JsonNode userInput) {
    }
}
