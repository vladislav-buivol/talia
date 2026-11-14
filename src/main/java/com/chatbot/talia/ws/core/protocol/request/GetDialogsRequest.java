package com.chatbot.talia.ws.core.protocol.request;

public record GetDialogsRequest(Integer limit, String cursor) implements ChatActionGetRequest {
    public GetDialogsRequest(int limit) {
        this(limit, null);
    }
}
