package com.chatbot.talia.ws.core.protocol;

public interface ChatRequest {
    ChatAction action();

    record Command(ChatAction action, String threadId, Integer limit, String cursor,
                   String text, String model, String clientId) implements ChatRequest {
    }
}
