package com.chatbot.talia.ws.codex.protocol.response.decoder;

import com.chatbot.talia.ws.codex.protocol.response.JsonRpcError;
import com.chatbot.talia.ws.codex.protocol.response.JsonRpcErrorResponse;
import com.chatbot.talia.ws.codex.protocol.response.JsonRpcResponse;
import com.chatbot.talia.ws.codex.protocol.response.JsonRpcSuccessResponse;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class CodexMessageDecoder {

    private final ObjectMapper mapper;

    public CodexMessageDecoder(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public JsonNode readMessage(String json) {
        JsonNode message = mapper.readTree(json);
        if (message == null || !message.isObject()) {
            throw new IllegalArgumentException("Expected a JSON object");
        }
        return message;
    }

    public Long readResponseId(JsonNode message) {
        if (message.has("method") || !message.has("id")
                || message.has("result") == message.has("error")) {
            throw new IllegalArgumentException(
                    "Expected a response with id and exactly one of result or error"
            );
        }

        JsonNode id = message.get("id");
        if (message.has("error") && id.isNull()) {
            return null;
        }
        return readLong(id, "Response ID");
    }

    public JsonRpcResponse decodeResponse(
            JsonNode message,
            Class<? extends JsonRpcSuccessResponse<?>> expectedType
    ) {
        Long id = readResponseId(message);

        if (message.has("error")) {
            return new ErrorResponse(id, readError(message.get("error")));
        }

        if (expectedType == null) {
            throw new IllegalArgumentException("No response class registered for ID " + id);
        }

        return mapper.treeToValue(message, expectedType);
    }

    public Notification decodeNotification(JsonNode message) {
        if (message.has("id")) {
            throw new IllegalArgumentException("A notification cannot contain an id");
        }

        String method = readMethod(message);
        return new Notification(
                NotificationMethod.fromWireName(method),
                method,
                message.get("params"),
                message
        );
    }

    public ServerRequest decodeServerRequest(JsonNode message) {
        String method = readMethod(message);
        JsonNode id = message.get("id");

        RequestId requestId;
        if (id != null && id.isString()) {
            requestId = new StringRequestId(id.stringValue());
        } else {
            requestId = new NumericRequestId(readLong(id, "Server request ID"));
        }

        return new ServerRequest(
                requestId,
                ServerRequestMethod.fromWireName(method),
                method,
                message.get("params"),
                message
        );
    }

    private static String readMethod(JsonNode message) {
        JsonNode method = message.get("method");
        if (method == null || !method.isString() || method.stringValue().isBlank()
                || message.has("result") || message.has("error")) {
            throw new IllegalArgumentException("Expected a message with a method");
        }
        return method.stringValue();
    }

    private static long readLong(JsonNode node, String field) {
        if (node == null || !node.isIntegralNumber() || !node.canConvertToLong()) {
            throw new IllegalArgumentException(field + " must be a 64-bit integer");
        }
        return node.asLong();
    }

    private static JsonRpcError readError(JsonNode error) {
        if (!error.isObject() || !error.path("message").isString()) {
            throw new IllegalArgumentException("Expected an error object with a message");
        }

        return new JsonRpcError(
                readLong(error.get("code"), "Error code"),
                error.get("message").stringValue(),
                error.get("data")
        );
    }

    public record ErrorResponse(Long id, JsonRpcError error)
            implements JsonRpcErrorResponse {

        @Override
        public Long getId() {
            return id;
        }

        @Override
        public JsonRpcError getError() {
            return error;
        }
    }

    public record Notification(
            NotificationMethod method, String wireMethod, JsonNode params, JsonNode raw
    ) {
    }

    public record ServerRequest(
            RequestId id, ServerRequestMethod method, String wireMethod,
            JsonNode params, JsonNode raw
    ) {
    }

    public sealed interface RequestId permits NumericRequestId, StringRequestId {
    }

    public record NumericRequestId(long value) implements RequestId {
    }

    public record StringRequestId(String value) implements RequestId {
    }
}
