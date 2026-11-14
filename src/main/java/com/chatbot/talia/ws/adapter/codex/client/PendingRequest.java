package com.chatbot.talia.ws.adapter.codex.client;

import com.chatbot.talia.ws.adapter.codex.protocol.response.JsonRpcResponse;
import com.chatbot.talia.ws.adapter.codex.protocol.response.JsonRpcSuccessResponse;

import java.util.concurrent.CompletableFuture;

//todo dive deeper into logic behind it
public record PendingRequest<R extends JsonRpcSuccessResponse<?>>(
        Class<R> responseType,
        CompletableFuture<R> future) {

    public void complete(JsonRpcResponse response) {
        future.complete(responseType.cast(response));
    }

    void onSendCompleted(Void sent, Throwable error) {
        if (error != null) {
            future.completeExceptionally(error);
        }
    }
}
