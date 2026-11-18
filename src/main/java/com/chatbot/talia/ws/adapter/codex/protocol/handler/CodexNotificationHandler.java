package com.chatbot.talia.ws.adapter.codex.protocol.handler;

import com.chatbot.talia.ws.adapter.codex.client.CodexMessageReporter;
import com.chatbot.talia.ws.adapter.codex.protocol.CodexMessageCodec;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("codex")
public final class CodexNotificationHandler
        implements MessageHandler<CodexMessageCodec.Notification> {

    private final CodexMessageReporter reporter;
    private volatile java.util.function.Consumer<CodexMessageCodec.Notification> listener;

    public CodexNotificationHandler(CodexMessageReporter reporter) {
        this.reporter = reporter;
    }

    public void setListener(java.util.function.Consumer<CodexMessageCodec.Notification> listener) {
        this.listener = listener;
    }

    @Override
    public void handle(CodexMessageCodec.Notification notification) {
        reporter.reportNotification(notification);
        java.util.function.Consumer<CodexMessageCodec.Notification> current = listener;
        if (current != null) current.accept(notification);
    }
}
