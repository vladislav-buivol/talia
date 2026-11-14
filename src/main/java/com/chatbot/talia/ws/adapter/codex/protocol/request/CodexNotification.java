package com.chatbot.talia.ws.adapter.codex.protocol.request;

public interface CodexNotification<P> {
    String method();

    P params();
}
