package com.chatbot.talia.ws.codex.client;


import com.chatbot.talia.ws.codex.listener.CodexWebSocketListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.WebSocket;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

@Component
@Profile("codex")
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
        client.connect();
        client.ping("ping");
        //client.send();
    }

    public void ping(String message) {
        connection.ping(message);
    }

    private void send(String message) {
        connection.send(message);
    }


    public void connect() {
        try {
            connection.open();
            initialize();
        } catch (ExecutionException e) {
            throw new RuntimeException(e);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } catch (TimeoutException e) {
            throw new RuntimeException(e);
        }
    }

    private void initialize() {
        //todo create proper implementation
        String initReq = "{\n" +
                "  \"method\": \"initialize\",\n" +
                "  \"id\": 0,\n" +
                "  \"params\": {\n" +
                "    \"clientInfo\": {\n" +
                "      \"name\": \"codex_vscode\",\n" +
                "      \"title\": \"Codex VS Code Extension\",\n" +
                "      \"version\": \"0.1.0\"\n" +
                "    }\n" +
                "  }\n" +
                "}";
        System.out.println("sending");
        System.out.println(initReq);
        connection.send(initReq);
        try {
            Thread.sleep(5000);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }
}


