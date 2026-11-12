package com.chatbot.talia.ws.codex.protocol.response;

public interface JsonRpcSuccessResponse<R> extends JsonRpcResponse {
    R getResult();
}