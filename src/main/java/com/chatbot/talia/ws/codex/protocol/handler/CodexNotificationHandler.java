package com.chatbot.talia.ws.codex.protocol.handler;

import com.chatbot.talia.ws.codex.client.CodexMessageReporter;
import com.chatbot.talia.ws.codex.protocol.response.decoder.CodexMessageDecoder;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("codex")
public final class CodexNotificationHandler
        implements MessageHandler<CodexMessageDecoder.Notification> {

    private final CodexMessageReporter reporter;

    public CodexNotificationHandler(CodexMessageReporter reporter) {
        this.reporter = reporter;
    }

    @Override
    public void handle(CodexMessageDecoder.Notification notification) {
        reporter.reportNotification(notification);
    }
}