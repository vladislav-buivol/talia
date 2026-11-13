package com.chatbot.talia.ws.codex.client;

import com.chatbot.talia.ws.codex.listener.CodexWebSocketListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

public class CodexWebSocketConnection implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(CodexWebSocketConnection.class);
    static final int SEND_QUEUE_CAPACITY = 64;
    private static final long CLOSE_TIMEOUT_SECONDS = 2;
    private final URI serverAddress;
    private final WebSocket.Listener listener;
    private final HttpClient httpClient;
    private final CodexMessageDispatcher dispatcher;
    private final ExecutorService sender = new ThreadPoolExecutor(
            1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(SEND_QUEUE_CAPACITY),
            Thread.ofVirtual().name("codex-sender").factory()
    );
    private final Set<CompletableFuture<?>> pendingSends = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean cleanupStarted = new AtomicBoolean();
    private final CompletableFuture<Void> closeCompletion = new CompletableFuture<>();
    private volatile WebSocket webSocket;

    public CodexWebSocketConnection(URI serverAddress, CodexMessageDispatcher dispatcher) {
        this(serverAddress, dispatcher, HttpClient.newHttpClient());
    }

    CodexWebSocketConnection(URI serverAddress, CodexMessageDispatcher dispatcher, HttpClient httpClient) {
        this.serverAddress = serverAddress;
        this.dispatcher = dispatcher;
        this.listener = new CodexWebSocketListener(dispatcher, this::onPeerClose, this::abort);
        this.httpClient = httpClient;
    }

    public synchronized WebSocket open() throws ExecutionException, InterruptedException, TimeoutException {
        Throwable cause = dispatcher.closeCause();
        if (cause != null) {
            throw new IllegalStateException("Connection is closed", cause);
        }
        if (webSocket != null) {
            throw new IllegalStateException("Connection is already open");
        }
        CompletableFuture<WebSocket> opening = httpClient
                .newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .buildAsync(serverAddress, listener);
        try {
            webSocket = opening.get(5, TimeUnit.SECONDS);
        } catch (InterruptedException | TimeoutException error) {
            opening.thenAccept(WebSocket::abort);
            abort(error);
            throw error;
        }
        cause = dispatcher.closeCause();
        if (cause != null) {
            if (!webSocket.isInputClosed()) {
                webSocket.abort();
            }
            throw new IllegalStateException("Connection closed while opening", cause);
        }
        return webSocket;
    }


    public CompletableFuture<WebSocket> ping(String message) {
        WebSocket socket = webSocket;
        CompletableFuture<WebSocket> result = trackSend(socket);
        if (!result.isDone()) {
            try {
                socket.sendPing(createPingPayloadFromString(message)).whenComplete((sent, error) -> {
                    if (error == null) {
                        result.complete(sent);
                    } else {
                        result.completeExceptionally(error);
                    }
                });
            } catch (RuntimeException error) {
                result.completeExceptionally(error);
            }
        }
        return result;
    }

    public CompletableFuture<Void> send(String message) {
        return send(message, () -> false);
    }

    CompletableFuture<Void> send(String message, BooleanSupplier requestFinished) {
        WebSocket socket = webSocket;
        CompletableFuture<Void> result = trackSend(socket);
        if (!result.isDone()) {
            try {
                sender.execute(new SendTask(message, result, socket, requestFinished, this::abort));
                log.debug("OUT: {}", message);
            } catch (RejectedExecutionException error) {
                result.completeExceptionally(error);
            }
        }
        return result;
    }

    private <T> CompletableFuture<T> trackSend(WebSocket socket) {
        CompletableFuture<T> result = new CompletableFuture<>();
        pendingSends.add(result);
        result.whenComplete((sent, error) -> pendingSends.remove(result));

        Throwable cause = dispatcher.closeCause();
        if (cause != null) {
            result.completeExceptionally(cause);
        } else if (socket == null || socket.isOutputClosed()) {
            result.completeExceptionally(new IllegalStateException("Connection is not open for sending"));
        }
        return result;
    }

    public ByteBuffer createPingPayloadFromString(String val) {
        byte[] bytes = val.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 125) {
            throw new IllegalArgumentException("Ping payload is too large! Max is 125 bytes, but got: " + bytes.length);
        }
        return ByteBuffer.wrap(bytes);
    }


    @Override
    public void close() {
        closeAsync();
    }

    public CompletableFuture<Void> closeAsync() {
        IOException cause = new IOException("Codex connection closed");
        if (dispatcher.beginClose(cause)) {
            sender.shutdownNow();
            WebSocket socket = webSocket;
            if (socket == null || socket.isInputClosed()) {
                finishClose(null, null);
            } else {
                sendClose(socket);
            }
            failPendingWorkAsync(cause);
        }
        return closeCompletion.copy();
    }

    private void sendClose(WebSocket socket) {
        try {
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "")
                    .copy()
                    .orTimeout(CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .whenComplete((closed, error) -> finishClose(socket,
                            error instanceof CompletionException && error.getCause() != null ? error.getCause() : error));
        } catch (RuntimeException error) {
            finishClose(socket, error);
        }
    }

    private void onPeerClose(Throwable cause) {
        if (dispatcher.beginClose(cause)) {
            sender.shutdownNow();
            finishClose(null, null);
            failPendingWorkAsync(cause);
        }
    }

    void abort(Throwable cause) {
        boolean closingStarted = dispatcher.beginClose(cause);
        if (closingStarted) {
            sender.shutdownNow();
        }
        finishClose(webSocket, cause);
        if (closingStarted) {
            failPendingWorkAsync(cause);
        }
    }

    private void failPendingWorkAsync(Throwable cause) {
        Thread.startVirtualThread(() -> {
            pendingSends.forEach(result -> result.completeExceptionally(cause));
            dispatcher.failPendingRequests(cause);
        });
    }

    private void finishClose(WebSocket socket, Throwable failure) {
        if (!cleanupStarted.compareAndSet(false, true)) {
            return;
        }

        try {
            if (socket != null && (failure != null || !socket.isInputClosed())) {
                socket.abort();
            }
        } catch (RuntimeException error) {
            failure = recordCloseFailure(failure, error);
        }
        try {
            httpClient.shutdown();
        } catch (RuntimeException error) {
            failure = recordCloseFailure(failure, error);
        }

        if (failure == null) {
            closeCompletion.complete(null);
        } else {
            log.warn("Failed to close the Codex WebSocket connection", failure);
            closeCompletion.completeExceptionally(failure);
        }
    }

    private static Throwable recordCloseFailure(Throwable failure, RuntimeException error) {
        if (failure == null) {
            return error;
        }
        if (failure != error) {
            failure.addSuppressed(error);
        }
        return failure;
    }
}
