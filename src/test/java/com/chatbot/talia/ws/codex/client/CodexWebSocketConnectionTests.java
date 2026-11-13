package com.chatbot.talia.ws.codex.client;

import com.chatbot.talia.ws.codex.protocol.CodexMessageCodec;
import com.chatbot.talia.ws.codex.protocol.handler.CodexMessageHandlerRegistry;
import com.chatbot.talia.ws.codex.protocol.response.InitializeResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class CodexWebSocketConnectionTests {
    private static final URI SERVER = URI.create("ws://localhost:4222");
    private final CodexMessageDispatcher dispatcher = new CodexMessageDispatcher(
            new CodexMessageCodec(new ObjectMapper()), CodexMessageHandlerRegistry.create(new CodexMessageReporter()));
    private final WebSocket socket = mock(WebSocket.class);
    private WebSocket.Listener listener;

    @Test
    void synchronousSendFailureCompletesTheSendFuture() throws Exception {
        IllegalStateException cause = new IllegalStateException("send failed");
        when(socket.sendText("request", true)).thenThrow(cause);
        try (CodexWebSocketConnection connection = openConnection()) {
            assertSame(cause, failureOf(connection.send("request")));
        }
    }

    @Test
    void peerCloseFailsQueuedWorkAndLetsTheWebSocketReply() throws Exception {
        CountDownLatch sending = new CountDownLatch(1);
        when(socket.sendText("in flight", true)).thenAnswer(invocation -> {
            sending.countDown();
            return new CompletableFuture<WebSocket>();
        });

        try (CodexWebSocketConnection connection = openConnection()) {
            CompletableFuture<InitializeResponse> reply = new CompletableFuture<>();
            dispatcher.register(1L, new PendingRequest<>(InitializeResponse.class, reply));
            CompletableFuture<Void> inFlight = connection.send("in flight");
            assertTrue(sending.await(2, TimeUnit.SECONDS));
            CompletableFuture<Void> queued = connection.send("queued");
            listener.onClose(socket, WebSocket.NORMAL_CLOSURE, "remote close");

            Throwable cause = failureOf(reply);
            assertInstanceOf(IOException.class, cause);
            assertSame(cause, failureOf(queued));
            failureOf(inFlight);
            assertSame(cause, failureOf(connection.send("too late")));
            assertSame(cause, assertThrows(IllegalStateException.class, connection::open).getCause());
            verify(socket, never()).sendText("queued", true);
            verify(socket, never()).abort();
            verify(socket, never()).sendClose(anyInt(), anyString());
        }
    }

    @Test
    void localCloseCompletesAfterWritingTheCloseFrame() throws Exception {
        when(socket.sendClose(WebSocket.NORMAL_CLOSURE, "")).thenReturn(CompletableFuture.completedFuture(socket));
        try (CodexWebSocketConnection connection = openConnection()) {
            CompletableFuture<Void> closing = connection.closeAsync();
            verify(socket).sendClose(WebSocket.NORMAL_CLOSURE, "");
            assertInstanceOf(IOException.class, failureOf(connection.send("too late")));
            closing.get(1, TimeUnit.SECONDS);
            verify(socket).abort();
        }
    }

    @Test
    void closeFrameWriteTimeoutFailsLocalClose() throws Exception {
        when(socket.sendClose(WebSocket.NORMAL_CLOSURE, "")).thenReturn(new CompletableFuture<>());
        try (CodexWebSocketConnection connection = openConnection()) {
            CompletableFuture<Void> closing = connection.closeAsync();
            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> closing.get(5, TimeUnit.SECONDS));
            assertInstanceOf(TimeoutException.class, failure.getCause());
            verify(socket).abort();
        }
    }

    @Test
    void localClosePreservesWriteAndCleanupFailures() throws Exception {
        IOException writeFailure = new IOException("Close frame write failed");
        IllegalStateException cleanupFailure = new IllegalStateException("Socket cleanup failed");
        when(socket.sendClose(WebSocket.NORMAL_CLOSURE, "")).thenReturn(CompletableFuture.failedFuture(writeFailure));
        doThrow(cleanupFailure).when(socket).abort();
        try (CodexWebSocketConnection connection = openConnection()) {
            Throwable failure = failureOf(connection.closeAsync());
            assertSame(writeFailure, failure);
            assertArrayEquals(new Throwable[] {cleanupFailure}, failure.getSuppressed());
        }
    }

    @Test
    void pendingRequestCallbackCanWaitForClose() throws Exception {
        CompletableFuture<WebSocket> closeFrame = new CompletableFuture<>();
        when(socket.sendClose(WebSocket.NORMAL_CLOSURE, "")).thenReturn(closeFrame);
        try (CodexWebSocketConnection connection = openConnection()) {
            CompletableFuture<InitializeResponse> reply = new CompletableFuture<>();
            dispatcher.register(1L, new PendingRequest<>(InitializeResponse.class, reply));
            CompletableFuture<Void> callback = reply.handle((response, error) -> {
                connection.closeAsync().orTimeout(5, TimeUnit.SECONDS).join();
                return null;
            });

            CompletableFuture<Void> closing = connection.closeAsync();
            verify(socket).sendClose(WebSocket.NORMAL_CLOSURE, "");
            closeFrame.complete(socket);

            closing.get(2, TimeUnit.SECONDS);
            callback.get(2, TimeUnit.SECONDS);
            assertInstanceOf(IOException.class, failureOf(reply));
        }
    }

    @Test
    void sendTimeoutClosesTheConnectionBeforeAnotherSend() throws Exception {
        CompletableFuture<WebSocket> stalledSend = new CompletableFuture<>() {
            @Override
            public WebSocket get(long timeout, TimeUnit unit) throws TimeoutException {
                throw new TimeoutException("send timed out");
            }
        };
        when(socket.sendText("stalled", true)).thenReturn(stalledSend);

        try (CodexWebSocketConnection connection = openConnection()) {
            assertInstanceOf(TimeoutException.class, failureOf(connection.send("stalled")));
            assertInstanceOf(TimeoutException.class, failureOf(connection.send("next")));
            verify(socket, never()).sendText("next", true);
        }
    }

    @Test
    void rejectsWorkWhenTheSendQueueIsFull() throws Exception {
        CountDownLatch sending = new CountDownLatch(1);
        when(socket.sendText("in flight", true)).thenAnswer(invocation -> {
            sending.countDown();
            return new CompletableFuture<WebSocket>();
        });

        try (CodexWebSocketConnection connection = openConnection()) {
            connection.send("in flight");
            assertTrue(sending.await(2, TimeUnit.SECONDS));
            for (int i = 0; i < CodexWebSocketConnection.SEND_QUEUE_CAPACITY; i++) {
                assertFalse(connection.send("queued").isDone());
            }
            assertInstanceOf(RejectedExecutionException.class, failureOf(connection.send("overflow")));
            verify(socket, never()).sendText("overflow", true);
        }
    }

    @Test
    void pingPayloadUsesTheUtf8ByteLength() throws Exception {
        try (CodexWebSocketConnection connection = openConnection()) {
            String message = "é".repeat(62) + "x";
            assertEquals(message, StandardCharsets.UTF_8.decode(connection.createPingPayloadFromString(message)).toString());
            assertThrows(IllegalArgumentException.class,
                    () -> connection.createPingPayloadFromString(message + "x"));
        }
    }

    private CodexWebSocketConnection openConnection() throws Exception {
        HttpClient httpClient = mock(HttpClient.class);
        WebSocket.Builder builder = mock(WebSocket.Builder.class);
        when(httpClient.newWebSocketBuilder()).thenReturn(builder);
        when(builder.connectTimeout(any(Duration.class))).thenReturn(builder);
        ArgumentCaptor<WebSocket.Listener> captured = ArgumentCaptor.forClass(WebSocket.Listener.class);
        when(builder.buildAsync(eq(SERVER), captured.capture()))
                .thenReturn(CompletableFuture.completedFuture(socket));
        CodexWebSocketConnection connection = new CodexWebSocketConnection(SERVER, dispatcher, httpClient);
        connection.open();
        listener = captured.getValue();
        return connection;
    }

    private static Throwable failureOf(CompletableFuture<?> future) {
        return assertThrows(ExecutionException.class, () -> future.get(2, TimeUnit.SECONDS)).getCause();
    }
}
