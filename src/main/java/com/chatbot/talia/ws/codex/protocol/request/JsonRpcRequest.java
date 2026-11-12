package com.chatbot.talia.ws.codex.protocol.request;


public interface JsonRpcRequest<P> {

    Long id();

    String method();

    P params();
}