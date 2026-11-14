package com.chatbot.talia.ws.core.protocol.request;

public record StartNewDialogRequest(String model) implements ChatActionPostRequest {
}
