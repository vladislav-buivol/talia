package com.chatbot.talia.ws.codex.client;

import com.chatbot.talia.ws.codex.protocol.response.JsonRpcResponse;
import com.chatbot.talia.ws.codex.protocol.response.JsonRpcSuccessResponse;

import java.util.concurrent.CompletableFuture;

//todo dive deeper into logic behind it
public record PendingRequest<R extends JsonRpcSuccessResponse<?>>(
        Class<R> responseType,
        CompletableFuture<R> future) {

    void complete(JsonRpcResponse response) {
        future.complete(responseType.cast(response));
    }

}