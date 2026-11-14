package com.chatbot.talia.ws.core.protocol.request;

public record ConnectAsWriterRequest(String threadId) implements ChatActionPostRequest {
    public ConnectAsWriterRequest() {
        this(null);
    }
}
