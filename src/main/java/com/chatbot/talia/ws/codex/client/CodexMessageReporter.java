package com.chatbot.talia.ws.codex.client;

import com.chatbot.talia.ws.codex.protocol.response.JsonRpcErrorResponse;
import com.chatbot.talia.ws.codex.protocol.response.decoder.CodexMessageDecoder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public final class CodexMessageReporter {

    private static final Logger log =
            LoggerFactory.getLogger(CodexMessageReporter.class);

    public void reportNotification(CodexMessageDecoder.Notification notification) {
        log.debug("Codex notification received: {}", notification.wireMethod());
    }

    public void reportServerRequest(CodexMessageDecoder.ServerRequest request) {
        log.warn("Unhandled Codex server request: {}", request.wireMethod());
    }

    public void reportUnmatchedError(JsonRpcErrorResponse error) {
        log.warn("Unmatched Codex error response: {}", error.getError().message());
    }
}