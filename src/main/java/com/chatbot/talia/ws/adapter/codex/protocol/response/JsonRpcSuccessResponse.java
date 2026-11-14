package com.chatbot.talia.ws.adapter.codex.protocol.response;

public interface JsonRpcSuccessResponse<R> extends JsonRpcResponse {
    R getResult();
}