package com.chatbot.talia.ws.codex.client;

import com.chatbot.talia.ws.codex.listener.CodexWebSocketListener;
import com.chatbot.talia.ws.codex.protocol.JsonConverter;
import com.chatbot.talia.ws.codex.protocol.handler.CodexNotificationHandler;
import com.chatbot.talia.ws.codex.protocol.handler.CodexResponseHandler;
import com.chatbot.talia.ws.codex.protocol.handler.CodexServerRequestHandler;
import com.chatbot.talia.ws.codex.protocol.handler.MessageHandler;
import com.chatbot.talia.ws.codex.protocol.request.*;
import com.chatbot.talia.ws.codex.protocol.response.*;
import com.chatbot.talia.ws.codex.protocol.response.decoder.CodexMessageDecoder;
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
    private final JsonConverter jsonConverter;
    private final CodexMessageDecoder decoder;
    private final CodexWebSocketConnection connection;
    private final ConcurrentHashMap<Long, PendingRequest<?>> pendingRequests = new ConcurrentHashMap<>();
    private final MessageHandler<CodexMessageDecoder.Notification> notificationHandler;
    private final MessageHandler<CodexMessageDecoder.ServerRequest> serverRequestHandler;
    private final CodexResponseHandler codexResponseHandler;

    public CodexWebSocketClient(
            @Value("${talia.codex.url:ws://127.0.0.1:4222}") String serverUrl,
            JsonConverter jsonConverter,
            CodexMessageDecoder decoder,
            WebSocket.Listener listener,
            CodexNotificationHandler notificationHandler,
            CodexServerRequestHandler serverRequestHandler, CodexResponseHandler codexResponseHandler) {
        this.jsonConverter = jsonConverter;
        this.decoder = decoder;
        this.notificationHandler = notificationHandler;
        this.serverRequestHandler = serverRequestHandler;
        this.codexResponseHandler = codexResponseHandler;

        this.connection = new CodexWebSocketConnection(
                URI.create(serverUrl), listener);

        //todo perhaps add dispatcher to prevent cycle dependencies
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

    public void onTextMessage(String json) {
        ResponseLookup lookup = new ResponseLookup();
        JsonRpcResponse message;
        try {
            message = decoder.readMessage(json, lookup::findType);
        } catch (RuntimeException error) {
            if (lookup.pending == null) {
                throw error;
            }
            lookup.pending.future().completeExceptionally(error);
            return;
        }

        switch (message) {
            case CodexMessageDecoder.Notification notification -> notificationHandler.handle(notification);
            case CodexMessageDecoder.ServerRequest request -> serverRequestHandler.handle(request);
            case JsonRpcResponse response -> completePendingRequest(response, lookup.pending);
        }
    }

    //todo investigate why it is different
    private void completePendingRequest(JsonRpcResponse response, PendingRequest<?> pending) {
        if (pending == null) {
            if (response instanceof JsonRpcErrorResponse error) {
                codexResponseHandler.handle(error);
            }
            return;
        }

        if (response instanceof JsonRpcErrorResponse error) {
            pending.future().completeExceptionally(
                    new IllegalStateException(error.getError().message())
            );
        } else {
            pending.complete(response);
        }
    }

    private final class ResponseLookup {
        private PendingRequest<?> pending;

        private Class<? extends JsonRpcSuccessResponse<?>> findType(long id) {
            pending = pendingRequests.get(id);
            if (pending == null) {
                return null;
            }
            return pending.responseType();
        }
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

        CodexMessageReporter reporter = new CodexMessageReporter();

        CodexNotificationHandler notificationHandler =
                new CodexNotificationHandler(reporter);

        CodexServerRequestHandler serverRequestHandler =
                new CodexServerRequestHandler(reporter);

        CodexResponseHandler responseHandler =
                new CodexResponseHandler(reporter);

        String serverUrl = "ws://127.0.0.1:4222";

        try (CodexWebSocketClient client = new CodexWebSocketClient(
                serverUrl,
                converter,
                decoder,
                new CodexWebSocketListener(),
                notificationHandler,
                serverRequestHandler,
                responseHandler
        )) {
            client.connect();

            client.initialize().get(10, TimeUnit.SECONDS);

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
