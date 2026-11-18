package com.chatbot.talia.ws.adapter.codex.protocol.handler;

import com.chatbot.talia.ws.adapter.codex.protocol.response.JsonRpcErrorResponse;
import com.chatbot.talia.ws.adapter.codex.protocol.CodexMessageCodec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public final class CodexMessageReporter {

    private static final Logger log =
            LoggerFactory.getLogger(CodexMessageReporter.class);

    public void reportNotification(CodexMessageCodec.Notification notification) {
        if (notification.params() == null) return;
        String itemType = notification.params().item() == null
                ? null
                : notification.params().item().type();
        log.debug("Codex notification: method={}, threadId={}, turnId={}, itemType={}",
                notification.wireMethod(),
                notification.params().threadId(),
                notification.params().turnId(),
                itemType);
    }

    public void reportServerRequest(CodexMessageCodec.ServerRequest request) {
        log.warn("Unhandled Codex server request: {}", request.wireMethod());
    }

    public void reportUnmatchedError(JsonRpcErrorResponse error) {
        log.warn("Unmatched Codex error response: {}", error.getError().message());
    }
}
