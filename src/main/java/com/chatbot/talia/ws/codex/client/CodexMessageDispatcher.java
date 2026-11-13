package com.chatbot.talia.ws.codex.client;

import com.chatbot.talia.ws.codex.protocol.CodexMessageCodec;
import com.chatbot.talia.ws.codex.protocol.handler.CodexMessageHandlerRegistry;
import com.chatbot.talia.ws.codex.protocol.response.JsonRpcErrorResponse;
import com.chatbot.talia.ws.codex.protocol.response.JsonRpcResponse;
import com.chatbot.talia.ws.codex.protocol.response.JsonRpcSuccessResponse;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public final class CodexMessageDispatcher {
    private final CodexMessageCodec messageCodec;
    private final CodexMessageHandlerRegistry handlerRegistry;
    private final ConcurrentHashMap<Long, PendingRequest<?>> pendingRequests = new ConcurrentHashMap<>();

    CodexMessageDispatcher(CodexMessageCodec messageCodec, CodexMessageHandlerRegistry handlerRegistry) {
        this.messageCodec = messageCodec;
        this.handlerRegistry = handlerRegistry;
    }

    boolean register(long id, PendingRequest<?> pending) {
        if (pendingRequests.putIfAbsent(id, pending) != null) {
            return false;
        }

        pending.future().orTimeout(10, TimeUnit.SECONDS);
        pending.future().whenComplete((result, error) -> pendingRequests.remove(id, pending));
        return true;
    }

    public void onTextMessage(String json) {
        ResponseLookup lookup = new ResponseLookup();
        JsonRpcResponse message;
        try {
            message = messageCodec.decode(json, lookup::findType);
        } catch (RuntimeException error) {
            if (lookup.pending == null) {
                throw error;
            }
            lookup.pending.future().completeExceptionally(error);
            return;
        }

        if (lookup.pending != null) {
            completePendingRequest(message, lookup.pending);
            return;
        }

        handlerRegistry.get(message.type()).handle(message);
    }

    public void onConnectionClosed(Throwable cause) {
        for (PendingRequest<?> pending : pendingRequests.values()) {
            pending.future().completeExceptionally(cause);
        }
    }

    private void completePendingRequest(JsonRpcResponse response, PendingRequest<?> pending) {
        if (response instanceof JsonRpcErrorResponse error) {
            pending.future().completeExceptionally(new IllegalStateException(error.getError().message()));
        } else {
            pending.complete(response);
        }
    }

    private final class ResponseLookup {
        private PendingRequest<?> pending;

        private Class<? extends JsonRpcSuccessResponse<?>> findType(long id) {
            pending = pendingRequests.get(id);
            if (pending == null) {
                return null;
            }
            return pending.responseType();
        }
    }
}
