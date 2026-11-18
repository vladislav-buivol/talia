package com.chatbot.talia.ws.adapter.codex.protocol.handler;

import com.chatbot.talia.ws.adapter.codex.client.CodexMessageReporter;
import com.chatbot.talia.ws.adapter.codex.protocol.CodexMessageCodec;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("codex")
public final class CodexServerRequestHandler
        implements MessageHandler<CodexMessageCodec.ServerRequest> {

    private final CodexMessageReporter reporter;
    private volatile java.util.function.Consumer<CodexMessageCodec.ServerRequest> receiver;

    public CodexServerRequestHandler(CodexMessageReporter reporter) {
        this.reporter = reporter;
    }

    public void setReceiver(java.util.function.Consumer<CodexMessageCodec.ServerRequest> receiver) {
        this.receiver = receiver;
    }

    @Override
    public void handle(CodexMessageCodec.ServerRequest request) {
        java.util.function.Consumer<CodexMessageCodec.ServerRequest> current = receiver;
        if (current == null) reporter.reportServerRequest(request);
        else current.accept(request);
    }
}
