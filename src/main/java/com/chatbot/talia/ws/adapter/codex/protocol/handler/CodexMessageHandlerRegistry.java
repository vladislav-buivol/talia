package com.chatbot.talia.ws.adapter.codex.protocol.handler;

import com.chatbot.talia.ws.adapter.codex.client.CodexMessageReporter;
import com.chatbot.talia.ws.adapter.codex.protocol.CodexMessageCodec;
import com.chatbot.talia.ws.adapter.codex.protocol.response.JsonRpcResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@Profile("codex")
public final class CodexMessageHandlerRegistry {
    private final Map<JsonRpcResponse.Type, MessageHandler<JsonRpcResponse>> handlers;

    public CodexMessageHandlerRegistry(
            CodexNotificationHandler notificationHandler,
            CodexServerRequestHandler serverRequestHandler,
            CodexResponseHandler responseHandler) {
        handlers = Map.of(
                JsonRpcResponse.Type.NOTIFICATION,
                message -> notificationHandler.handle((CodexMessageCodec.Notification) message),
                JsonRpcResponse.Type.SERVER_REQUEST,
                message -> serverRequestHandler.handle((CodexMessageCodec.ServerRequest) message),
                JsonRpcResponse.Type.RESPONSE, responseHandler
        );
    }

    public static CodexMessageHandlerRegistry create(CodexMessageReporter reporter) {
        return new CodexMessageHandlerRegistry(
                new CodexNotificationHandler(reporter),
                new CodexServerRequestHandler(reporter),
                new CodexResponseHandler(reporter)
        );
    }

    public MessageHandler<JsonRpcResponse> get(JsonRpcResponse.Type type) {
        return handlers.get(type);
    }
}
