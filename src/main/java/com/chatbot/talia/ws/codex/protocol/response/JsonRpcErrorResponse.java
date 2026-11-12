package com.chatbot.talia.ws.codex.protocol.response;

public interface JsonRpcErrorResponse extends JsonRpcResponse {
    JsonRpcError getError();
}