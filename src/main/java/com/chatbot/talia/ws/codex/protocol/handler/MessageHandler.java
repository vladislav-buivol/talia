package com.chatbot.talia.ws.codex.protocol.handler;

import com.chatbot.talia.ws.codex.protocol.response.JsonRpcResponse;

public interface MessageHandler<T extends JsonRpcResponse> {

    void handle(T message);
}