package com.chatbot.talia.ws.codex.protocol.response;

public interface JsonRpcResponse {
    enum Type {
        NOTIFICATION,
        SERVER_REQUEST,
        RESPONSE
    }

    default Type type() {
        return Type.RESPONSE;
    }

    /** Returns the numeric ID, or null if the message has no numeric ID. */
    Long getId();
}
