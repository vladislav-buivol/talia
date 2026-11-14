package com.chatbot.talia.ws.core.protocol.request;

import com.chatbot.talia.ws.core.protocol.ChatRequest;

public interface ChatActionPostRequest extends ChatRequest {
    @Override
    default RequestType type() {
        return RequestType.POST;
    }
}
