package com.chatbot.talia.ws.codex.client;

import com.chatbot.talia.ws.codex.listener.CodexWebSocketListener;
import com.chatbot.talia.ws.codex.protocol.JsonConverter;
import com.chatbot.talia.ws.codex.protocol.request.*;
import com.chatbot.talia.ws.codex.protocol.response.*;
import com.chatbot.talia.ws.codex.protocol.response.decoder.CodexMessageDecoder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.WebSocket;
import java.util.Objects;
import java.util.concurrent.*;

@Component
@Profile("codex")
public class CodexWebSocketClient implements AutoCloseable {

    private final JsonConverter jsonConverter;
    private final CodexMessageDecoder decoder;
    private final CodexWebSocketConnection connection;
    private final ConcurrentHashMap<Long, PendingRequest<?>> pendingRequests =
            new ConcurrentHashMap<>();

    public CodexWebSocketClient(
            @Value("${talia.codex.url:ws://127.0.0.1:4222}") String serverUrl,
            JsonConverter jsonConverter,
            CodexMessageDecoder decoder,
            WebSocket.Listener listener
    ) {
        this.jsonConverter = jsonConverter;
        this.decoder = decoder;
        this.connection = new CodexWebSocketConnection(URI.create(serverUrl), listener);
        if (listener instanceof CodexWebSocketListener codexListener) {
            codexListener.setClient(this);
        }
    }

    public void connect() {
        try {
            connection.open();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while opening Codex connection", error);
        } catch (ExecutionException | TimeoutException error) {
            throw new IllegalStateException("Could not open Codex connection", error);
        }
    }

    public CompletableFuture<WebSocket> ping(String message) {
        return connection.ping(message);
    }

    public CompletableFuture<InitializeResponse> initialize() {
        InitializeRequest request = InitializeRequest.create(0L, "talia", "Talia", "0.1.0");
        return send(request, InitializeResponse.class);
    }

    public CompletableFuture<Void> sendNotification(CodexNotification<?> notification) {
        return connection.send(jsonConverter.toJson(notification));
    }

    public <R extends JsonRpcSuccessResponse<?>> CompletableFuture<R> send(CodexRequest<?> request, Class<R> responseType) {
        Objects.requireNonNull(responseType);
        Long id = request.id();
        if (id == null) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("Request ID is required"));
        }


        CompletableFuture<R> future = new CompletableFuture<>();
        PendingRequest<R> pending = new PendingRequest<>(responseType, future);

        if (pendingRequests.putIfAbsent(id, pending) != null) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("Request ID is already pending: " + id)
            );
        }

        future.orTimeout(10, TimeUnit.SECONDS);
        future.whenComplete((result, error) -> pendingRequests.remove(id, pending));
        String json = jsonConverter.toJson(request);
        connection.send(json).whenComplete((sent, error) -> {
            if (error != null) {
                future.completeExceptionally(error);
            }
        });

        return future;
    }

    public void onMessage(String json) {
        JsonNode message = decoder.readMessage(json);

        if (message.has("method")) {
            if (message.has("id")) {
                handleServerRequest(decoder.decodeServerRequest(message));
            } else {
                handleNotification(decoder.decodeNotification(message));
            }
            return;
        }

        Long id = decoder.readResponseId(message);
        PendingRequest<?> pending = id == null ? null : pendingRequests.get(id);

        if (pending == null) {
            if (message.has("error")) {
                JsonRpcErrorResponse error =
                        (JsonRpcErrorResponse) decoder.decodeResponse(message, null);
                System.err.println("Unmatched Codex error: " + error.getError().message());
            }
            return;
        }

        try {
            JsonRpcResponse response = decoder.decodeResponse(message, pending.responseType());

            if (response instanceof JsonRpcErrorResponse error) {
                pending.future().completeExceptionally(
                        new IllegalStateException(error.getError().message())
                );
            } else {
                pending.complete(response);
            }
        } catch (RuntimeException error) {
            pending.future().completeExceptionally(error);
        }
    }

    private void handleNotification(CodexMessageDecoder.Notification notification) {
        System.out.println("Codex notification: " + notification.wireMethod());
    }

    private void handleServerRequest(CodexMessageDecoder.ServerRequest request) {
        System.out.println("Codex server request: " + request.wireMethod());
    }

    public void onConnectionClosed(Throwable cause) {
        for (PendingRequest<?> pending : pendingRequests.values()) {
            pending.future().completeExceptionally(cause);
        }
    }

    @Override
    public void close() {
        try {
            connection.close();
        } catch (Exception error) {
            throw new IllegalStateException("Failed to close Codex connection", error);
        } finally {
            onConnectionClosed(new IOException("Codex connection closed"));
        }
    }

    public static void main(String[] args) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonConverter converter = new JsonConverter(mapper);
        CodexMessageDecoder decoder = new CodexMessageDecoder(mapper);
        String serverUrl = "ws://127.0.0.1:4222";

        try (
                CodexWebSocketClient client = new CodexWebSocketClient(
                        serverUrl, converter, decoder, new CodexWebSocketListener())) {

            client.connect();
            client.initialize().get(10, TimeUnit.SECONDS);
            client.sendNotification(InitializedNotification.create()).get(10, TimeUnit.SECONDS);

            ThreadStartRequest threadRequest = ThreadStartRequest.create(1L, "gpt-5.6-luna");
            ThreadStartResponse threadResponse = client.send(threadRequest, ThreadStartResponse.class)
                    .get(10, TimeUnit.SECONDS);
            String threadId = threadResponse.getResult().thread().id();

            TurnStartRequest turnRequest =
                    TurnStartRequest.create(2L, threadId, "hello from intellij idea");
            client.send(turnRequest, TurnStartResponse.class).get(10, TimeUnit.SECONDS);

            System.out.println("Turn started. Press Enter after the answer to close.");
            System.in.read();
        }
    }
}
