package com.chatbot.talia.ws.core.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

public interface ChatResponse {
    List<ChatAction> availableAction();

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Event(String type, List<ChatAction> availableAction, Object data) implements ChatResponse {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Dialog(String threadId, String name, String preview, List<ChatAction> availableAction) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record DialogPage(List<Dialog> dialogs, String nextCursor) {
    }

    record Message(String role, String text) {
    }
}
