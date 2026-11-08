package com.chatbot.talia.ws.codex.client;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class CodexWebSocketConnection {
    private final URI serverAddress;
    private final WebSocket.Listener listener;

    public CodexWebSocketConnection(URI serverAddress, WebSocket.Listener listener) {
        this.serverAddress = serverAddress;
        this.listener = listener;
    }

    public WebSocket open() throws ExecutionException, InterruptedException, TimeoutException {
        HttpClient httpClient = HttpClient.newHttpClient();
        return httpClient
                .newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .buildAsync(serverAddress, listener)
                .get(5, TimeUnit.SECONDS);
    }
}
