package com.chatbot.talia.ws.adapter.codex.client.rpc;

import com.chatbot.talia.ws.adapter.codex.config.CodexSettings;
import com.chatbot.talia.ws.adapter.codex.protocol.CodexMessageCodec;
import com.chatbot.talia.ws.adapter.codex.protocol.handler.CodexMessageHandlerRegistry;
import com.chatbot.talia.ws.adapter.codex.protocol.response.decoder.NotificationMethod;
import com.chatbot.talia.ws.adapter.codex.protocol.response.JsonRpcErrorResponse;
import com.chatbot.talia.ws.adapter.codex.protocol.response.JsonRpcResponse;
import com.chatbot.talia.ws.adapter.codex.protocol.response.JsonRpcSuccessResponse;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public final class CodexMessageDispatcher {
    private final CodexMessageCodec messageCodec;
    private final CodexMessageHandlerRegistry handlerRegistry;
    private final ConcurrentHashMap<Long, PendingRequest<?>> pendingRequests = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CodexTurn> turns = new ConcurrentHashMap<>();
    private final AtomicReference<Throwable> closeCause = new AtomicReference<>();
    private final Duration defaultTimeout;

    CodexMessageDispatcher(CodexMessageCodec messageCodec, CodexMessageHandlerRegistry handlerRegistry) {
        this(messageCodec, handlerRegistry, CodexSettings.DEFAULT_RPC_TIMEOUT);
    }

    CodexMessageDispatcher(CodexMessageCodec messageCodec, CodexMessageHandlerRegistry handlerRegistry, Duration timeout) {
        this.messageCodec = messageCodec;
        this.handlerRegistry = handlerRegistry;
        this.defaultTimeout = timeout;
    }

    void register(long id, PendingRequest<?> pending) {
        if (pendingRequests.putIfAbsent(id, pending) != null) {
            pending.future().completeExceptionally(new IllegalStateException("Duplicate JSON-RPC request ID: " + id));
            return;
        }
        Duration timeout = pending.timeout() == null ? defaultTimeout : pending.timeout();
        pending.future().orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS);
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
        var params = notification.params();
        if (params == null || params.threadId() == null) {
            return;
        }
        boolean commentary = (method == NotificationMethod.ITEM_STARTED || method == NotificationMethod.ITEM_COMPLETED)
                && params.item() != null && params.item().isCommentary();
        if (!commentary && method != NotificationMethod.ITEM_AGENT_MESSAGE_DELTA && method != NotificationMethod.TURN_COMPLETED) {
            return;
        }

        CodexTurn turn = turns.get(params.threadId());
        if (turn != null) {
            turn.onNotification(notification);
        }
    }

    public boolean beginClose(Throwable cause) {
        return closeCause.compareAndSet(null, cause);
    }

    public void failPendingRequests(Throwable cause) {
        for (PendingRequest<?> pending : pendingRequests.values()) {
            pending.future().completeExceptionally(cause);
        }
        for (CodexTurn turn : turns.values()) {
            turn.result().completeExceptionally(cause);
        }
    }

    public Throwable closeCause() {
        return closeCause.get();
    }

    private void completePendingRequest(JsonRpcResponse response, PendingRequest<?> pending) {
        if (response instanceof JsonRpcErrorResponse error) {
            pending.future().completeExceptionally(new CodexRpcException(error.getError().code(), error.getError().message(), error.getError().data(), pending.method()));
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
