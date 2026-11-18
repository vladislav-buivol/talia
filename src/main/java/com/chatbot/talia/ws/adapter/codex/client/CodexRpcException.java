package com.chatbot.talia.ws.adapter.codex.client;

import tools.jackson.databind.JsonNode;

/** Preserves the wire error code and optional details for callers and diagnostics. */
public final class CodexRpcException extends RuntimeException {
    private final long code;
    private final JsonNode data;
    private final String method;

    public CodexRpcException(long code, String message, JsonNode data, String method) {
        super(message);
        this.code = code;
        this.data = data;
        this.method = method;
    }

    public long code() { return code; }
    public JsonNode data() { return data; }
    public String method() { return method; }
}
