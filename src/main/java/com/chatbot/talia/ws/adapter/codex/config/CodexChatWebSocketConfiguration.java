package com.chatbot.talia.ws.adapter.codex.config;

import com.chatbot.talia.ws.adapter.codex.CodexChatBackend;
import com.chatbot.talia.ws.adapter.codex.client.CodexClient;
import com.chatbot.talia.ws.adapter.codex.protocol.request.InitializeRequest.ClientInfo;
import com.chatbot.talia.ws.core.ChatService;
import com.chatbot.talia.ws.core.websocket.ChatWebSocketHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import tools.jackson.databind.ObjectMapper;

@Configuration
@EnableWebSocket
@Profile("codex")
public class CodexChatWebSocketConfiguration implements WebSocketConfigurer {
    private static final String CHAT_WS_ENDPOINT = "/ws/chat";
    private final ObjectMapper mapper;
    private final String serverUrl;
    private final ClientInfo clientInfo;

    public CodexChatWebSocketConfiguration(
            ObjectMapper mapper,
            @Value("${talia.codex.url}") String serverUrl,
            @Value("${talia.codex.client.name}") String clientName,
            @Value("${talia.codex.client.title}") String clientTitle,
            @Value("${talia.codex.client.version}") String clientVersion) {
        this.mapper = mapper;
        this.serverUrl = serverUrl;
        this.clientInfo = new ClientInfo(clientName, clientTitle, clientVersion);
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(chatWebSocketHandler(), CHAT_WS_ENDPOINT);
    }

    @Bean
    public WebSocketHandler chatWebSocketHandler() {
        CodexChatBackend backend = new CodexChatBackend(() -> CodexClient.create(serverUrl), clientInfo);
        return new ChatWebSocketHandler(mapper, new ChatService(mapper, backend));
    }
}
