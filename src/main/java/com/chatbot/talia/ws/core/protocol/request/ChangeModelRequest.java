package com.chatbot.talia.ws.core.protocol.request;

public record ChangeModelRequest(String model) implements ChatActionPostRequest {
}
