package com.chatbot.talia.ws.codex.client;

import org.springframework.context.annotation.Profile;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;


public class CodexWebSocketConnection {
    private final URI serverAddress;
    private final WebSocket.Listener listener;
    private WebSocket webSocket;

    public CodexWebSocketConnection(URI serverAddress, WebSocket.Listener listener) {
        this.serverAddress = serverAddress;
        this.listener = listener;
    }

    public WebSocket open() throws ExecutionException, InterruptedException, TimeoutException {
        HttpClient httpClient = HttpClient.newHttpClient();
        webSocket = httpClient
                .newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .buildAsync(serverAddress, listener)
                .get(5, TimeUnit.SECONDS);
        return webSocket;
    }


    public void ping(String message) {
        ByteBuffer payload = createPingPayloadFromString(message);
        webSocket.sendPing(payload);
    }

    public void send(CharSequence request) {
        webSocket.sendText(request, true);
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


}
