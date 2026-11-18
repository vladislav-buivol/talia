package com.chatbot.talia.ws.adapter.codex.client.transport;

import java.net.http.WebSocket;
import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

public class SendTask implements Runnable {
    private final String message;
    private final CompletableFuture<Void> result;
    private final WebSocket socket;
    private final BooleanSupplier requestFinished;
    private final Consumer<Throwable> onSendTimeout;
    private final Duration sendTimeout;

    SendTask(String message, CompletableFuture<Void> result, WebSocket socket,
             BooleanSupplier requestFinished, Consumer<Throwable> onSendTimeout) {
        this.message = message;
        this.result = result;
        this.socket = socket;
        this.requestFinished = requestFinished;
        this.onSendTimeout = onSendTimeout;
        this.sendTimeout = Duration.ofSeconds(10);
    }

    SendTask(String message, CompletableFuture<Void> result, WebSocket socket,
             BooleanSupplier requestFinished, Consumer<Throwable> onSendTimeout, Duration timeout) {
        this.message = message;
        this.result = result;
        this.socket = socket;
        this.requestFinished = requestFinished;
        this.onSendTimeout = onSendTimeout;
        this.sendTimeout = timeout;
    }

    @Override
    public void run() {
        if (result.isDone()) {
            return;
        }
        if (requestFinished.getAsBoolean()) {
            result.completeExceptionally(new CancellationException("Request finished before it could be sent"));
            return;
        }
        try {
            socket.sendText(message, true).get(sendTimeout.toMillis(), TimeUnit.MILLISECONDS);
            result.complete(null);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            result.completeExceptionally(error);
        } catch (ExecutionException error) {
            result.completeExceptionally(error.getCause());
        } catch (TimeoutException error) {
            onSendTimeout.accept(error);
            result.completeExceptionally(error);
        } catch (RuntimeException error) {
            result.completeExceptionally(error);
        }
    }
}
