package com.chatbot.talia.ws.adapter.codex.client;

import com.chatbot.talia.ws.adapter.codex.protocol.response.JsonRpcResponse;
import com.chatbot.talia.ws.adapter.codex.protocol.response.JsonRpcSuccessResponse;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/** A single JSON-RPC operation; completion, timeout and disconnect all retire its correlation ID. */
public record PendingRequest<R extends JsonRpcSuccessResponse<?>>(
        Class<R> responseType,
        CompletableFuture<R> future,
        String method,
        Duration timeout) {

    public PendingRequest {
        Objects.requireNonNull(responseType);
        Objects.requireNonNull(future);
    }

    public PendingRequest(Class<R> type, CompletableFuture<R> future) {
        this(type, future, "unknown", CodexSettings.DEFAULT_RPC_TIMEOUT);
    }

    public void complete(JsonRpcResponse response) {
        try {
            future.complete(responseType.cast(response));
        } catch (ClassCastException error) {
            future.completeExceptionally(error);
        }
    }

    void onSendCompleted(Void sent, Throwable error) {
        if (error != null) {
            future.completeExceptionally(error);
        }
    }
}
