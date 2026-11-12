package com.chatbot.talia.ws.codex.protocol.handler;

import com.chatbot.talia.ws.codex.client.CodexMessageReporter;
import com.chatbot.talia.ws.codex.protocol.response.JsonRpcErrorResponse;
import com.chatbot.talia.ws.codex.protocol.response.JsonRpcResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("codex")
public final class CodexResponseHandler implements MessageHandler<JsonRpcResponse> {

    private final CodexMessageReporter reporter;

    public CodexResponseHandler(CodexMessageReporter reporter) {
        this.reporter = reporter;
    }

    @Override
    public void handle(JsonRpcResponse message) {
        if (message instanceof JsonRpcErrorResponse error) {
            reporter.reportUnmatchedError(error);
        }
    }
}