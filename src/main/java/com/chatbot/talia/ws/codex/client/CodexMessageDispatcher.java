package com.chatbot.talia.ws.codex.client;

import com.chatbot.talia.ws.codex.protocol.CodexMessageCodec;
import com.chatbot.talia.ws.codex.protocol.handler.CodexMessageHandlerRegistry;
import com.chatbot.talia.ws.codex.protocol.response.JsonRpcErrorResponse;
import com.chatbot.talia.ws.codex.protocol.response.JsonRpcResponse;
import com.chatbot.talia.ws.codex.protocol.response.JsonRpcSuccessResponse;
import com.chatbot.talia.ws.codex.protocol.response.decoder.NotificationMethod;

import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public final class CodexMessageDispatcher {
    private final CodexMessageCodec messageCodec;
    private final CodexMessageHandlerRegistry handlerRegistry;
    private final ConcurrentHashMap<Long, PendingRequest<?>> pendingRequests = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CodexTurn> turns = new ConcurrentHashMap<>();
    private final AtomicReference<Throwable> closeCause = new AtomicReference<>();

    CodexMessageDispatcher(CodexMessageCodec messageCodec, CodexMessageHandlerRegistry handlerRegistry) {
        this.messageCodec = messageCodec;
        this.handlerRegistry = handlerRegistry;
    }

    void register(long id, PendingRequest<?> pending) {
        pendingRequests.put(id, pending);
        pending.future().orTimeout(10, TimeUnit.SECONDS);
        pending.future().whenComplete((result, error) -> pendingRequests.remove(id, pending));
        Throwable cause = closeCause();
        if (cause != null) {
            pending.future().completeExceptionally(cause);
        }
    }

    boolean registerTurn(String threadId, CodexTurn turn) {
        CodexTurn registered = turns.compute(threadId, (id, current) ->
                current == null || current.result().isDone() ? turn : current);
        if (registered != turn) {
            return false;
        }

        turn.result().whenComplete((result, error) -> turns.remove(threadId, turn));
        Throwable cause = closeCause();
        if (cause != null) {
            turn.result().completeExceptionally(cause);
        }
        return true;
    }

    CodexTurn trackedTurn(String threadId) {
        return turns.get(threadId);
    }

    void stopTrackingTurn(String threadId, CodexTurn turn) {
        if (turn != null && turns.remove(threadId, turn)) {
            turn.result().completeExceptionally(new CancellationException("Finished conversation " + threadId));
        }
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

        if (message instanceof CodexMessageCodec.Notification notification) {
            dispatchTurnNotification(notification);
        }
        handlerRegistry.get(message.type()).handle(message);
    }

    private void dispatchTurnNotification(CodexMessageCodec.Notification notification) {
        NotificationMethod method = notification.method();
        if (method != NotificationMethod.ITEM_AGENT_MESSAGE_DELTA && method != NotificationMethod.TURN_COMPLETED) {
            return;
        }
        if (notification.params() == null || !notification.params().path("threadId").isString()) {
            return;
        }

        CodexTurn turn = turns.get(notification.params().path("threadId").asString());
        if (turn != null) {
            turn.onNotification(notification);
        }
    }

    boolean beginClose(Throwable cause) {
        return closeCause.compareAndSet(null, cause);
    }

    void failPendingRequests(Throwable cause) {
        for (PendingRequest<?> pending : pendingRequests.values()) {
            pending.future().completeExceptionally(cause);
        }
        for (CodexTurn turn : turns.values()) {
            turn.result().completeExceptionally(cause);
        }
    }

    Throwable closeCause() {
        return closeCause.get();
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
