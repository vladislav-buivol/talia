package com.chatbot.talia.ws.adapter.codex.protocol.response;

/** Preserves arbitrary JSON-RPC error data without exposing Jackson to application code. */
public record JsonRpcError(long code, String message, Data data) {
    public record Data(String json) {
    }
}
