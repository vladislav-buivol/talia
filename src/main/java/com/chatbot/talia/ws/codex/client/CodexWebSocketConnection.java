package com.chatbot.talia.ws.codex.client;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.*;

//todo add logging
public class CodexWebSocketConnection implements AutoCloseable {
    private final URI serverAddress;
    private final WebSocket.Listener listener;
    private final HttpClient httpClient;

    private final ExecutorService sender = Executors.newSingleThreadExecutor(
            Thread.ofVirtual().name("codex-sender").factory()
    );
    private volatile WebSocket webSocket;

    public CodexWebSocketConnection(URI serverAddress, WebSocket.Listener listener) {
        this.serverAddress = serverAddress;
        this.listener = listener;
        this.httpClient = HttpClient.newHttpClient();
    }

    public synchronized WebSocket open() throws ExecutionException, InterruptedException, TimeoutException {
        if (webSocket != null) {
            throw new IllegalStateException("Connection is already open");
        }
        webSocket = httpClient
                .newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .buildAsync(serverAddress, listener)
                .get(5, TimeUnit.SECONDS);
        return webSocket;
    }


    public CompletableFuture<WebSocket> ping(String message) {
        ByteBuffer payload = createPingPayloadFromString(message);
        return webSocket.sendPing(payload);
    }

    //todo have a look why return type is a CompletableFuture<Void>
    public CompletableFuture<Void> send(String message) {
        CompletableFuture<Void> result = new CompletableFuture<>();

        try {
            System.out.println("OUT: " + message);
            sender.execute(new SendTask(message, result, webSocket));
        } catch (RejectedExecutionException error) {
            result.completeExceptionally(error);
        }

        return result;
    }

    public ByteBuffer createPingPayloadFromString(String val) {
        byte[] bytes = val.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 125) {
            throw new IllegalArgumentException("Ping payload is too large! Max is 125 bytes, but got: " + bytes.length);
        }
        ByteBuffer payload = ByteBuffer.allocate(Long.BYTES);
        payload.put(bytes);
        payload.flip();
        return payload;
    }


    @Override
    public void close() throws Exception {
        sender.shutdown();
        try {
            if (!sender.awaitTermination(10, TimeUnit.SECONDS)) {
                sender.shutdown();
            }
        } catch (InterruptedException e) {
            sender.shutdown();
            Thread.currentThread().interrupt();
        }
        //todo why it is necessary?
        WebSocket socket = webSocket;
        if (socket != null) {
            socket.abort();
        }
        httpClient.shutdown();
    }
}
