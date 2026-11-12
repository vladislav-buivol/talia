package com.chatbot.talia.ws.codex.client;

import java.net.http.WebSocket;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class SendTask implements Runnable {
    private final String message;
    private final CompletableFuture<Void> result;
    private final WebSocket socket;

    public SendTask(String message, CompletableFuture<Void> result, WebSocket socket) {
        this.message = message;
        this.result = result;
        this.socket = socket;
    }

    @Override
    public void run() {
        if (socket == null) {
            result.completeExceptionally(new IllegalStateException("Connection is not open"));
            return;
        }
        try {
            socket.sendText(message, true).get(10, TimeUnit.SECONDS);
            result.complete(null);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            result.completeExceptionally(error);
        } catch (ExecutionException | TimeoutException error) {
            result.completeExceptionally(error);
        }
    }

}
