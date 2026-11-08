package com.chatbot.talia.http.controller;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile({"!codex","ollama", "lmstudio"})
@RequestMapping("/api/chat")
public class DemoChatControllerMistrall {

    private final ChatClient chatClient;

    public DemoChatControllerMistrall(ChatClient.Builder builder) {
        this.chatClient = builder
                .defaultSystem("""
                        You are Talia, a helpful AI assistant.
                        Give clear and concise answers.
                        """)
                .build();
    }

    @PostMapping
    public ChatResponse chat(@RequestBody ChatRequest request) {

        String answer = chatClient.prompt()
                .user(request.message())
                .call()
                .content();

        return new ChatResponse(answer);
    }

    public record ChatRequest(String message) {
    }

    public record ChatResponse(String answer) {
    }
}
