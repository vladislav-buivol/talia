package com.chatbot.talia.ws.codex.listener;

import com.chatbot.talia.ws.codex.client.CodexWebSocketClient;

import java.io.IOException;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.CompletionStage;


public class CodexWebSocketListener implements WebSocket.Listener {
    private CodexWebSocketClient client;
    private final StringBuilder fragments = new StringBuilder();

    public CodexWebSocketListener() {
    }

    public void setClient(CodexWebSocketClient client) {
        this.client = Objects.requireNonNull(client);
    }

    @Override
    public void onOpen(WebSocket webSocket) {
        WebSocket.Listener.super.onOpen(webSocket);
    }

    @Override
    //todo use proper logging instead of System.out.println
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
        fragments.append(data);

        if (last) {
            String json = fragments.toString();
            fragments.setLength(0);

            System.out.println("onText: " + json);
            client.onMessage(json);
        }

        return WebSocket.Listener.super.onText(webSocket, data, last);
    }

    @Override
    public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
        System.out.println("onBinary");
        return WebSocket.Listener.super.onBinary(webSocket, data, last);
    }

    @Override
    public CompletionStage<?> onPing(WebSocket webSocket, ByteBuffer message) {
        System.out.println("onPing: " + convertToString(message));
        return WebSocket.Listener.super.onPing(webSocket, message);
    }

    @Override
    public CompletionStage<?> onPong(WebSocket webSocket, ByteBuffer message) {
        System.out.println("onPong: " + convertToString(message));
        return WebSocket.Listener.super.onPong(webSocket, message);
    }

    @Override
    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
        IOException cause = new IOException(
                "Codex connection closed. Status: " + statusCode
        );
        System.out.println("onClose");
        client.onConnectionClosed(cause);
        return WebSocket.Listener.super.onClose(webSocket, statusCode, reason);
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
        System.out.println("onError");
        client.onConnectionClosed(error);
        WebSocket.Listener.super.onError(webSocket, error);
    }

    private String convertToString(ByteBuffer message) {

        return StandardCharsets.UTF_8.decode(message.duplicate()).toString();
    }
}