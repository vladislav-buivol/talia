package com.chatbot.talia.ws.adapter.codex.client;

import com.chatbot.talia.ws.adapter.codex.protocol.CodexMessageCodec;
import com.chatbot.talia.ws.adapter.codex.protocol.handler.CodexMessageHandlerRegistry;
import com.chatbot.talia.ws.adapter.codex.protocol.request.CodexMethod;
import com.chatbot.talia.ws.adapter.codex.protocol.request.CodexNotification;
import com.chatbot.talia.ws.adapter.codex.protocol.request.CodexRequest;
import com.chatbot.talia.ws.adapter.codex.protocol.request.InitializeRequest;
import com.chatbot.talia.ws.adapter.codex.protocol.request.InitializedNotification;
import com.chatbot.talia.ws.adapter.codex.protocol.request.ThreadUnsubscribeRequest;
import com.chatbot.talia.ws.adapter.codex.protocol.request.TurnStartRequest;
import com.chatbot.talia.ws.adapter.codex.protocol.request.TurnInterruptRequest;
import com.chatbot.talia.ws.adapter.codex.protocol.response.TurnInterruptResponse;
import com.chatbot.talia.ws.adapter.codex.protocol.response.InitializeResponse;
import com.chatbot.talia.ws.adapter.codex.protocol.response.JsonRpcSuccessResponse;
import com.chatbot.talia.ws.adapter.codex.protocol.response.ThreadUnsubscribeResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.WebSocket;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

@Component
@Profile("codex")
public class CodexWebSocketClient implements AutoCloseable {
    private final CodexMessageCodec messageCodec;
    private final CodexWebSocketConnection connection;
    private final CodexMessageDispatcher dispatcher;
    private final AtomicLong nextRequestId = new AtomicLong();
    private final AtomicBoolean handshakeStarted = new AtomicBoolean();
    private volatile boolean initialized;
    private final CodexSettings settings;
    private final CodexMessageHandlerRegistry handlerRegistry;

    @org.springframework.beans.factory.annotation.Autowired
    public CodexWebSocketClient(
            @Value("${talia.codex.url}") String serverUrl,
            CodexMessageCodec messageCodec,
            CodexMessageHandlerRegistry handlerRegistry) {
        this(CodexSettings.local(URI.create(serverUrl)), messageCodec, handlerRegistry);
    }

    public CodexWebSocketClient(CodexSettings settings, CodexMessageCodec messageCodec,
            CodexMessageHandlerRegistry handlerRegistry) {
        this.settings = settings;
        this.handlerRegistry = handlerRegistry;
        this.messageCodec = messageCodec;
        this.dispatcher = new CodexMessageDispatcher(messageCodec, handlerRegistry, settings.rpcTimeout());
        this.connection = new CodexWebSocketConnection(settings.url(), dispatcher);
    }

    public CodexSettings settings() { return settings; }

    public void onServerRequest(java.util.function.Consumer<CodexMessageCodec.ServerRequest> callback) {
        handlerRegistry.onServerRequest(callback);
    }

    public CompletableFuture<Void> sendServerResult(CodexMessageCodec.RequestId id, Object result) {
        Throwable error = checkReady();
        return error != null ? CompletableFuture.failedFuture(error)
                : connection.send(messageCodec.encodeServerResult(id, result));
    }

    public CompletableFuture<Void> sendServerError(CodexMessageCodec.RequestId id, long code, String message) {
        Throwable error = checkReady();
        return error != null ? CompletableFuture.failedFuture(error)
                : connection.send(messageCodec.encodeServerError(id, code, message));
    }

    public CompletableFuture<InitializeResponse> connectAndInitialize(String name, String title, String version) {
        if (!handshakeStarted.compareAndSet(false, true)) {
            return CompletableFuture.failedFuture(new IllegalStateException("Codex initialization was already started"));
        }

        try {
            connection.open();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return CompletableFuture.failedFuture(error);
        } catch (ExecutionException error) {
            connection.abort(error.getCause());
            return CompletableFuture.failedFuture(error.getCause());
        } catch (TimeoutException | RuntimeException error) {
            connection.abort(error);
            return CompletableFuture.failedFuture(error);
        }

        InitializeRequest request = InitializeRequest.create(name, title, version);
        CompletableFuture<InitializeResponse> response = sendRequest(request, InitializeResponse.class);
        return response.thenCompose(this::sendInitialized)
                .thenCombine(response, this::markInitialized)
                .whenComplete(this::abortOnInitializationFailure)
                .copy();
    }

    private CompletableFuture<Void> sendInitialized(InitializeResponse response) {
        return sendEncodedNotification(InitializedNotification.create());
    }

    private InitializeResponse markInitialized(Void sent, InitializeResponse response) {
        Throwable cause = dispatcher.closeCause();
        if (cause != null) {
            throw new CompletionException(cause);
        }
        initialized = true;
        return response;
    }

    private void abortOnInitializationFailure(InitializeResponse response, Throwable error) {
        if (error != null && dispatcher.closeCause() == null) {
            connection.abort(error instanceof CompletionException ? error.getCause() : error);
        }
    }

    public CompletableFuture<WebSocket> ping(String message) {
        return connection.ping(message);
    }

    public CompletableFuture<Void> sendNotification(CodexNotification<?> notification) {
        Throwable failure = checkReady();
        if (failure != null) {
            return CompletableFuture.failedFuture(failure);
        }
        if (CodexMethod.INITIALIZED.value().equals(notification.method())) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("The client sends initialized during the handshake"));
        }
        return sendEncodedNotification(notification);
    }

    private CompletableFuture<Void> sendEncodedNotification(CodexNotification<?> notification) {
        try {
            return connection.send(messageCodec.encode(notification));
        } catch (RuntimeException error) {
            return CompletableFuture.failedFuture(error);
        }
    }

    public <R extends JsonRpcSuccessResponse<?>> CompletableFuture<R> send(
            CodexRequest<?> request, Class<R> responseType) {
        Throwable failure = checkReady();
        if (failure != null) {
            return CompletableFuture.failedFuture(failure);
        }
        if (CodexMethod.INITIALIZE.value().equals(request.method())) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("The client sends initialize during the handshake"));
        }
        return sendRequest(request, responseType);
    }

    private Throwable checkReady() {
        Throwable cause = dispatcher.closeCause();
        if (cause != null) {
            return cause;
        }
        return initialized ? null : new IllegalStateException("Codex session is not initialized");
    }

    /** Streams text on the WebSocket listener thread and returns the server's terminal turn outcome. */
    public CompletableFuture<CodexTurnResult> runTurn(TurnStartRequest request, Consumer<String> onText) {
        Throwable failure = checkReady();
        if (failure != null) {
            return CompletableFuture.failedFuture(failure);
        }

        String threadId = request.params().threadId();
        CodexTurn turn = new CodexTurn(onText, settings.maxEarlyNotifications(), settings.turnStartTimeout(),
                id -> send(TurnInterruptRequest.create(threadId, id), TurnInterruptResponse.class).thenApply(ignored -> null));
        if (!dispatcher.registerTurn(threadId, turn)) {
            return CompletableFuture.failedFuture(new IllegalStateException("Already tracking a turn on thread " + threadId));
        }
        sendRequest(request, turn.startRequest());
        return turn.result();
    }

    public CompletableFuture<Void> interruptTurn(String threadId) {
        CodexTurn turn = dispatcher.trackedTurn(threadId);
        return turn == null ? CompletableFuture.completedFuture(null) : turn.interrupt();
    }

    /** Unsubscribes this connection while preserving the conversation and its history. */
    CompletableFuture<ThreadUnsubscribeResponse> unsubscribeThread(String threadId) {
        Objects.requireNonNull(threadId);
        CodexTurn turn = dispatcher.trackedTurn(threadId);
        CompletableFuture<ThreadUnsubscribeResponse> response = send(
                ThreadUnsubscribeRequest.create(threadId), ThreadUnsubscribeResponse.class);
        return response.whenComplete(new ThreadUnsubscribe(threadId, turn, dispatcher)).copy();
    }

    private record ThreadUnsubscribe(String threadId, CodexTurn turn, CodexMessageDispatcher dispatcher)
            implements BiConsumer<ThreadUnsubscribeResponse, Throwable> {
        @Override
        public void accept(ThreadUnsubscribeResponse response, Throwable error) {
            if (error == null) {
                dispatcher.stopTrackingTurn(threadId, turn);
            }
        }
    }

    private <R extends JsonRpcSuccessResponse<?>> CompletableFuture<R> sendRequest(
            CodexRequest<?> request, Class<R> responseType) {
        PendingRequest<R> pending = new PendingRequest<>(Objects.requireNonNull(responseType),
                new CompletableFuture<>(), request.method(), timeoutFor(request.method()));
        sendRequest(request, pending);
        return pending.future();
    }

    private java.time.Duration timeoutFor(String method) {
        if (CodexMethod.TURN_START.value().equals(method)) return settings.turnStartTimeout();
        if (CodexMethod.TURN_INTERRUPT.value().equals(method)) return settings.interruptTimeout();
        return settings.rpcTimeout();
    }

    private void sendRequest(CodexRequest<?> request, PendingRequest<?> pending) {
        long id;
        String json;
        try {
            id = nextRequestId.getAndUpdate(Math::incrementExact);
            CodexRequest<?> outbound = request.withId(id);
            if (!Objects.equals(outbound.id(), id)) {
                throw new IllegalArgumentException("Request must use the assigned ID: " + id);
            }
            json = messageCodec.encode(outbound);
        } catch (RuntimeException error) {
            pending.future().completeExceptionally(error);
            return;
        }

        dispatcher.register(id, pending);
        connection.send(json, pending.future()::isDone).whenComplete(pending::onSendCompleted);
    }

    @Override
    public void close() {
        connection.close();
    }

    public CompletableFuture<Void> closeAsync() {
        return connection.closeAsync();
    }

    boolean isClosed() {
        return dispatcher.closeCause() != null;
    }
}
