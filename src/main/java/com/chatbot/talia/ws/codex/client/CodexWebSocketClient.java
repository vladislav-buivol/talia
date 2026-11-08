package com.chatbot.talia.ws.codex.client;


import com.chatbot.talia.ws.codex.listener.CodexWebSocketListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.WebSocket;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

@Component
public class CodexWebSocketClient {
    private static final String MODEL = "gpt-5.6-luna";
    private final URI serverAddress;
    private final CodexWebSocketConnection connection;

    public CodexWebSocketClient(
            @Value("${talia.codex.url:ws://127.0.0.1:4222}") String serverURL,
            WebSocket.Listener listener) {
        this.serverAddress = URI.create(serverURL);
        this.connection = new CodexWebSocketConnection(serverAddress, listener);
    }

    static void main() throws ExecutionException, InterruptedException, TimeoutException {
        CodexWebSocketClient client = new CodexWebSocketClient("ws://127.0.0.1:4222", new CodexWebSocketListener());
        WebSocket WebSocket = client.connect();
    }

    public WebSocket connect() {
        try {
            return connection.open();
        } catch (ExecutionException e) {
            throw new RuntimeException(e);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } catch (TimeoutException e) {
            throw new RuntimeException(e);
        }
    }

    public void ping() {

    }
}


