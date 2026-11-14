package com.chatbot.talia.ws.adapter.codex.protocol.handler;

import com.chatbot.talia.ws.adapter.codex.protocol.response.JsonRpcResponse;

public interface MessageHandler<T extends JsonRpcResponse> {

    void handle(T message);
}