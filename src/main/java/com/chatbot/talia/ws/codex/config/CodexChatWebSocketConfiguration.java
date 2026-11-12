package com.chatbot.talia.ws.codex.config;

import com.chatbot.talia.ws.codex.client.CodexMessageReporter;
import com.chatbot.talia.ws.codex.handler.CodexChatWebSocketHandler;
import com.chatbot.talia.ws.codex.listener.CodexWebSocketListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

import java.net.http.WebSocket;

@Configuration
@EnableWebSocket
@Profile("codex")
public class CodexChatWebSocketConfiguration implements WebSocketConfigurer {
    private static final String CODEX_CHAT_WS_ENDPOINT = "/ws/chat";

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(codexChatWebSocketHandler(), CODEX_CHAT_WS_ENDPOINT);
    }

    @Bean
    public WebSocketHandler codexChatWebSocketHandler() {
        return new CodexChatWebSocketHandler();
    }

    @Bean
    public WebSocket.Listener codexWebSocketListener() {
        return new CodexWebSocketListener();
    }

    @Bean
    public CodexMessageReporter reporter() {
        return new CodexMessageReporter();
    }
}
