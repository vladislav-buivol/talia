
package com.chatbot.talia.ws.codex.listener;

import com.chatbot.talia.ws.codex.client.CodexMessageDispatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

public class CodexWebSocketListener implements WebSocket.Listener {

    private static final Logger log =
            LoggerFactory.getLogger(CodexWebSocketListener.class);

    private final CodexMessageDispatcher dispatcher;
    private final StringBuilder fragments = new StringBuilder();

    public CodexWebSocketListener(CodexMessageDispatcher dispatcher) {
        this.dispatcher = Objects.requireNonNull(dispatcher);
    }

    @Override
    public void onOpen(WebSocket webSocket) {
        log.info("Codex WebSocket connection established");
        WebSocket.Listener.super.onOpen(webSocket);
    }

    @Override
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
        fragments.append(data);

        if (last) {
            String json = fragments.toString();
            fragments.setLength(0);

            log.debug("onText: {}", json);
            dispatcher.onTextMessage(json);
        }

        return WebSocket.Listener.super.onText(webSocket, data, last);
    }

    @Override
    public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
        log.debug("onBinary");
        return WebSocket.Listener.super.onBinary(webSocket, data, last);
    }

    @Override
    public CompletionStage<?> onPing(WebSocket webSocket, ByteBuffer message) {
        log.debug("onPing: {}", convertToString(message));
        return WebSocket.Listener.super.onPing(webSocket, message);
    }

    @Override
    public CompletionStage<?> onPong(WebSocket webSocket, ByteBuffer message) {
        log.debug("onPong: {}", convertToString(message));
        return WebSocket.Listener.super.onPong(webSocket, message);
    }

    @Override
    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
        IOException cause = new IOException(
                "Codex connection closed. Status: " + statusCode
        );

        log.info("onClose: status={}, reason={}", statusCode, reason);
        dispatcher.onConnectionClosed(cause);

        return WebSocket.Listener.super.onClose(webSocket, statusCode, reason);
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
        log.error("onError", error);
        dispatcher.onConnectionClosed(error);
        WebSocket.Listener.super.onError(webSocket, error);
    }

    private String convertToString(ByteBuffer message) {
        return StandardCharsets.UTF_8.decode(message.duplicate()).toString();
    }
}
