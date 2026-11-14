package com.chatbot.talia.ws.adapter.codex.protocol.response;

public interface JsonRpcErrorResponse extends JsonRpcResponse {
    JsonRpcError getError();
}