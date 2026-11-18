package com.chatbot.talia.ws.adapter.codex.client.rpc;

import com.chatbot.talia.ws.adapter.codex.protocol.response.JsonRpcError;

/** Preserves the JSON-RPC error code and details for callers. */
public final class CodexRpcException extends RuntimeException {
    private final long code;
    private final JsonRpcError.Data data;
    private final String method;

    public CodexRpcException(long code, String message, JsonRpcError.Data data, String method) {
        super(message);
        this.code = code;
        this.data = data;
        this.method = method;
    }

    public long code() {
        return code;
    }

    public JsonRpcError.Data data() {
        return data;
    }

    public String method() {
        return method;
    }
}
