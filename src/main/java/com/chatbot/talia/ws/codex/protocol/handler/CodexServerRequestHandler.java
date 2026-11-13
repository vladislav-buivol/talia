package com.chatbot.talia.ws.codex.protocol.handler;

import com.chatbot.talia.ws.codex.client.CodexMessageReporter;
import com.chatbot.talia.ws.codex.protocol.CodexMessageCodec;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("codex")
public final class CodexServerRequestHandler
        implements MessageHandler<CodexMessageCodec.ServerRequest> {

    private final CodexMessageReporter reporter;

    public CodexServerRequestHandler(CodexMessageReporter reporter) {
        this.reporter = reporter;
    }

    @Override
    public void handle(CodexMessageCodec.ServerRequest request) {
        reporter.reportServerRequest(request);
    }
}
