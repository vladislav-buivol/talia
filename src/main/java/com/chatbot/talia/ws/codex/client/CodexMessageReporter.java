package com.chatbot.talia.ws.codex.client;

import com.chatbot.talia.ws.codex.protocol.response.JsonRpcErrorResponse;
import com.chatbot.talia.ws.codex.protocol.CodexMessageCodec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public final class CodexMessageReporter {

    private static final Logger log =
            LoggerFactory.getLogger(CodexMessageReporter.class);

    public void reportNotification(CodexMessageCodec.Notification notification) {
        log.debug("{} Codex notification: method={}, threadId={}, turnId={}, type={}, text={}",
                switch (notification.params().path("item").path("type").asString()) {
                    case "userMessage" -> "[USER_MESSAGE " + notification.wireMethod() + "] >>>";
                    case "agentMessage" -> "[AGENT_MESSAGE " + notification.wireMethod() + "] <<<";
                    default -> "";
                },
                notification.wireMethod(),
                notification.params().path("threadId").asString(""),
                notification.params().path("turnId").asString(""),
                notification.params().path("item").path("type").asString(""),
                "userMessage".equals(notification.params().path("item").path("type").asString())
                        ? notification.params().path("item").path("content").path(0).path("text").asString("")
                        : notification.params().path("item").path("text").asString(""));
    }

    public void reportServerRequest(CodexMessageCodec.ServerRequest request) {
        log.warn("Unhandled Codex server request: {}", request.wireMethod());
    }

    public void reportUnmatchedError(JsonRpcErrorResponse error) {
        log.warn("Unmatched Codex error response: {}", error.getError().message());
    }
}
