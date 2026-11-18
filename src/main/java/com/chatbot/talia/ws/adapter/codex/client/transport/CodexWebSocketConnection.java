package com.chatbot.talia.ws.adapter.codex.client.transport;

import com.chatbot.talia.ws.adapter.codex.client.rpc.CodexMessageDispatcher;
import com.chatbot.talia.ws.adapter.codex.config.CodexSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.Set;

public class CodexWebSocketConnection implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(CodexWebSocketConnection.class);
    private final URI serverAddress;
    private final CodexSettings settings;
    private final WebSocket.Listener listener;
    private final HttpClient httpClient;
    private final CodexMessageDispatcher dispatcher;
    private final ExecutorService sender;
    private final Set<CompletableFuture<?>> pendingSends = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean cleanupStarted = new AtomicBoolean();
    private final CompletableFuture<Void> closeCompletion = new CompletableFuture<>();
    private volatile WebSocket webSocket;

    public CodexWebSocketConnection(URI serverAddress, CodexMessageDispatcher dispatcher) {
        this(serverAddress, dispatcher, CodexSettings.local(serverAddress));
    }

    public CodexWebSocketConnection(URI serverAddress, CodexMessageDispatcher dispatcher, CodexSettings settings) {
        this(serverAddress, dispatcher, HttpClient.newHttpClient(), settings);
    }

    public CodexWebSocketConnection(URI serverAddress, CodexMessageDispatcher dispatcher, HttpClient httpClient) {
        this(serverAddress, dispatcher, httpClient, CodexSettings.local(serverAddress));
    }

    CodexWebSocketConnection(URI serverAddress, CodexMessageDispatcher dispatcher, HttpClient httpClient, CodexSettings settings) {
        this.serverAddress = serverAddress;
        this.settings = settings;
        this.sender = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(settings.sendQueueCapacity()),
                Thread.ofVirtual().name("codex-sender").factory());
        this.dispatcher = dispatcher;
        this.listener = new CodexWebSocketListener(dispatcher, this::onPeerClose, this::abort, settings.maxMessageBytes());
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
                .connectTimeout(settings.connectTimeout())
                .buildAsync(serverAddress, listener);
        try {
            webSocket = opening.get(settings.connectTimeout().toMillis(), TimeUnit.MILLISECONDS);
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

    public CompletableFuture<Void> send(String message, BooleanSupplier requestFinished) {
        WebSocket socket = webSocket;
        CompletableFuture<Void> result = trackSend(socket);
        if (!result.isDone()) {
            try {
                sender.execute(new SendTask(message, result, socket, requestFinished, this::abort, settings.sendTimeout()));
                log.trace("WebSocket message enqueued");
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
                    .orTimeout(settings.closeTimeout().toMillis(), TimeUnit.MILLISECONDS)
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

    public void abort(Throwable cause) {
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
