package com.chatbot.talia.ws.codex.protocol.request;

public interface CodexNotification<P> {
    String method();

    P params();
}
