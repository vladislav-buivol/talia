package com.chatbot.talia.ws.adapter.codex.client.rpc;

import com.chatbot.talia.ws.adapter.codex.protocol.CodexMessageCodec;
import com.chatbot.talia.ws.adapter.codex.protocol.handler.CodexMessageHandlerRegistry;
import com.chatbot.talia.ws.adapter.codex.protocol.handler.CodexMessageReporter;
import com.chatbot.talia.ws.adapter.codex.protocol.response.InitializeResponse;
import com.chatbot.talia.ws.adapter.codex.protocol.response.ThreadStartResponse;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodexMessageDispatcherTests {

    private CodexMessageDispatcher dispatcher() {
        return new CodexMessageDispatcher(
                new CodexMessageCodec(new ObjectMapper()),
                CodexMessageHandlerRegistry.create(new CodexMessageReporter()),
                Duration.ofSeconds(2));
    }

    @Test
    void correlatesOutOfOrderResponsesByRequestId() throws Exception {
        CodexMessageDispatcher dispatcher = dispatcher();
        CompletableFuture<InitializeResponse> initialized = new CompletableFuture<>();
        CompletableFuture<ThreadStartResponse> started = new CompletableFuture<>();
        dispatcher.register(4L, new PendingRequest<>(InitializeResponse.class, initialized, "initialize",
                Duration.ofSeconds(2)));
        dispatcher.register(5L, new PendingRequest<>(ThreadStartResponse.class, started, "thread/start",
                Duration.ofSeconds(2)));

        dispatcher.onTextMessage("""
                {"id":5,"result":{"thread":{"id":"thread-5"}}}
                """);
        assertEquals("thread-5", started.get(1, TimeUnit.SECONDS).result().thread().id());
        assertFalse(initialized.isDone());

        dispatcher.onTextMessage("""
                {"id":4,"result":{}}
                """);
        assertEquals(4L, initialized.get(1, TimeUnit.SECONDS).id());
    }

    @Test
    void preservesRpcErrorCodeAndMethod() {
        CodexMessageDispatcher dispatcher = dispatcher();
        CompletableFuture<InitializeResponse> initialized = new CompletableFuture<>();
        dispatcher.register(7L, new PendingRequest<>(InitializeResponse.class, initialized, "initialize",
                Duration.ofSeconds(2)));

        dispatcher.onTextMessage("""
                {"id":7,"error":{"code":-32602,"message":"invalid params"}}
                """);

        ExecutionException failure = assertThrows(ExecutionException.class,
                () -> initialized.get(1, TimeUnit.SECONDS));
        CodexRpcException rpc = assertInstanceOf(CodexRpcException.class, failure.getCause());
        assertEquals(-32602L, rpc.code());
        assertEquals("initialize", rpc.method());
    }

    @Test
    void expiresPendingRequestAndIgnoresItsLateResponse() {
        CodexMessageDispatcher dispatcher = dispatcher();
        CompletableFuture<InitializeResponse> initialized = new CompletableFuture<>();
        dispatcher.register(9L, new PendingRequest<>(InitializeResponse.class, initialized, "initialize",
                Duration.ofMillis(30)));

        ExecutionException failure = assertThrows(ExecutionException.class,
                () -> initialized.get(2, TimeUnit.SECONDS));
        assertInstanceOf(TimeoutException.class, failure.getCause());

        dispatcher.onTextMessage("""
                {"id":9,"result":{}}
                """);
        assertTrue(initialized.isCompletedExceptionally());
    }

    @Test
    void disconnectFailsEveryPendingRequest() {
        CodexMessageDispatcher dispatcher = dispatcher();
        CompletableFuture<InitializeResponse> initialized = new CompletableFuture<>();
        CompletableFuture<ThreadStartResponse> started = new CompletableFuture<>();
        dispatcher.register(10L, new PendingRequest<>(InitializeResponse.class, initialized));
        dispatcher.register(11L, new PendingRequest<>(ThreadStartResponse.class, started));

        IOException disconnected = new IOException("WebSocket disconnected");
        assertTrue(dispatcher.beginClose(disconnected));
        dispatcher.failPendingRequests(disconnected);

        ExecutionException initializeFailure = assertThrows(ExecutionException.class,
                () -> initialized.get(1, TimeUnit.SECONDS));
        ExecutionException startFailure = assertThrows(ExecutionException.class,
                () -> started.get(1, TimeUnit.SECONDS));
        assertSame(disconnected, initializeFailure.getCause());
        assertSame(disconnected, startFailure.getCause());
    }

    @Test
    void rejectsRegistrationAfterClose() {
        CodexMessageDispatcher dispatcher = dispatcher();
        IOException disconnected = new IOException("WebSocket disconnected");
        assertTrue(dispatcher.beginClose(disconnected));

        CompletableFuture<InitializeResponse> pending = new CompletableFuture<>();
        dispatcher.register(12L, new PendingRequest<>(InitializeResponse.class, pending));
        ExecutionException failure = assertThrows(ExecutionException.class,
                () -> pending.get(1, TimeUnit.SECONDS));
        assertSame(disconnected, failure.getCause());
    }
}
