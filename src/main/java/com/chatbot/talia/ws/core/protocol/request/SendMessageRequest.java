package com.chatbot.talia.ws.core.protocol.request;

public record SendMessageRequest(String text) implements ChatActionPostRequest {
}
