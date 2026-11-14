package com.chatbot.talia.ws.adapter.codex.protocol.request;


public interface JsonRpcRequest<P> {

    Long id();

    String method();

    P params();
}