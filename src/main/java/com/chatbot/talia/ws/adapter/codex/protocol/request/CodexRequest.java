package com.chatbot.talia.ws.adapter.codex.protocol.request;

public interface CodexRequest<P> extends JsonRpcRequest<P> {
    /** Returns a copy with the assigned wire ID. */
    CodexRequest<P> withId(long id);
}
