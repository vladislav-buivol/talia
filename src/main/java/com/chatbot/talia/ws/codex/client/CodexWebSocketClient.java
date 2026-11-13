package com.chatbot.talia.ws.codex.client;

import com.chatbot.talia.ws.codex.listener.CodexWebSocketListener;
import com.chatbot.talia.ws.codex.protocol.CodexMessageCodec;
import com.chatbot.talia.ws.codex.protocol.handler.CodexMessageHandlerRegistry;
import com.chatbot.talia.ws.codex.protocol.request.*;
import com.chatbot.talia.ws.codex.protocol.response.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.WebSocket;
import java.util.Objects;
import java.util.concurrent.*;

@Component
@Profile("codex")
public class CodexWebSocketClient implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(CodexWebSocketClient.class);
    private final CodexMessageCodec messageCodec;
    private final CodexWebSocketConnection connection;
    private final CodexMessageDispatcher dispatcher;

    public CodexWebSocketClient(
            @Value("${talia.codex.url:ws://127.0.0.1:4222}") String serverUrl,
            CodexMessageCodec messageCodec,
            CodexMessageHandlerRegistry handlerRegistry) {
        this.messageCodec = messageCodec;
        this.dispatcher = new CodexMessageDispatcher(messageCodec, handlerRegistry);
        this.connection = new CodexWebSocketConnection(URI.create(serverUrl), new CodexWebSocketListener(dispatcher));
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

    public CompletableFuture<InitializeResponse> initialize(long id, String name, String title, String version) {
        InitializeRequest request = InitializeRequest.create(id, name, title, version);
        return send(request, InitializeResponse.class);
    }

    public CompletableFuture<Void> sendNotification(CodexNotification<?> notification) {
        return connection.send(messageCodec.encode(notification));
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

        if (!dispatcher.register(id, pending)) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("Request ID is already pending: " + id)
            );
        }

        String json = messageCodec.encode(request);
        connection.send(json).whenComplete((sent, error) -> {
            if (error != null) {
                future.completeExceptionally(error);
            }
        });

        return future;
    }

    @Override
    public void close() {
        try {
            connection.close();
        } catch (Exception error) {
            throw new IllegalStateException("Failed to close Codex connection", error);
        } finally {
            dispatcher.onConnectionClosed(new IOException("Codex connection closed"));
        }
    }


    public static void main(String[] args) throws Exception {
        CodexMessageCodec messageCodec = new CodexMessageCodec(new ObjectMapper());

        CodexMessageHandlerRegistry handlerRegistry = CodexMessageHandlerRegistry.create(new CodexMessageReporter());

        String serverUrl = "ws://127.0.0.1:4222";

        try (CodexWebSocketClient client = new CodexWebSocketClient(
                serverUrl,
                messageCodec,
                handlerRegistry
        )) {
            client.connect();

            client.initialize(0l, "talia", "Tali", "0.1.0").get(10, TimeUnit.SECONDS);

            client.sendNotification(InitializedNotification.create())
                    .get(10, TimeUnit.SECONDS);

            ThreadStartRequest threadRequest =
                    ThreadStartRequest.create(1L, "gpt-5.6-luna");

            ThreadStartResponse threadResponse =
                    client.send(threadRequest, ThreadStartResponse.class)
                            .get(10, TimeUnit.SECONDS);

            String threadId = threadResponse.getResult().thread().id();

            TurnStartRequest turnRequest =
                    TurnStartRequest.create(2L, threadId, "hello from intellij idea");

            client.send(turnRequest, TurnStartResponse.class)
                    .get(10, TimeUnit.SECONDS);

            log.info("Turn started. Press Enter after the answer to close.");
            System.in.read();
        }
    }

}
