package com.chatbot.talia.ws.core.protocol.request;

public record DeleteChatRequest(String threadId) implements ChatActionDeleteRequest {
}
