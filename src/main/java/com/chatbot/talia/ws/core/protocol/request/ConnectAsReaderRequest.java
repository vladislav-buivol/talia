package com.chatbot.talia.ws.core.protocol.request;

public record ConnectAsReaderRequest(String threadId) implements ChatActionPostRequest {
    public ConnectAsReaderRequest() {
        this(null);
    }
}
