package com.chatbot.talia.http.controller;

import com.chatbot.talia.http.service.SupportAssistantServiceCodexHttp;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
class SupportAssistantControllerCodex {

    private final SupportAssistantServiceCodexHttp service;

    SupportAssistantControllerCodex(SupportAssistantServiceCodexHttp service) {
        this.service = service;
    }

    @GetMapping(path = "/api/v{version}/chat", version = "1.0")
    String chat(@RequestParam String query) {
        return service.generateResponse(query);
    }
}