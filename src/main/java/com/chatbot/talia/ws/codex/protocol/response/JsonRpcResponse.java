package com.chatbot.talia.ws.codex.protocol.response;

public interface JsonRpcResponse {
    /** Returns the numeric ID, or null if the message has no numeric ID. */
    Long getId();
}
