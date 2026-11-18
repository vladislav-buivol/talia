package com.chatbot.talia.ws.adapter.codex.protocol;

import com.chatbot.talia.ws.adapter.codex.protocol.message.CodexServerRequestParams;
import com.chatbot.talia.ws.adapter.codex.protocol.message.NotificationParams;
import com.chatbot.talia.ws.adapter.codex.protocol.response.decoder.NotificationMethod;
import com.chatbot.talia.ws.adapter.codex.protocol.response.decoder.ServerRequestMethod;
import com.chatbot.talia.ws.adapter.codex.protocol.response.JsonRpcError;
import com.chatbot.talia.ws.adapter.codex.protocol.response.JsonRpcErrorResponse;
import com.chatbot.talia.ws.adapter.codex.protocol.response.JsonRpcResponse;
import com.chatbot.talia.ws.adapter.codex.protocol.response.JsonRpcSuccessResponse;
import com.chatbot.talia.ws.adapter.codex.protocol.response.ThreadTurnsListResponse;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.function.LongFunction;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Component
public class CodexMessageCodec {

    private final ObjectMapper mapper;

    public CodexMessageCodec(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public String encodeServerResult(RequestId id, Object result) {
        tools.jackson.databind.node.ObjectNode reply = mapper.createObjectNode();
        writeId(reply, id);
        reply.set("result", mapper.valueToTree(result));
        return mapper.writeValueAsString(reply);
    }

    public String encodeServerError(RequestId id, long code, String message) {
        tools.jackson.databind.node.ObjectNode reply = mapper.createObjectNode();
        writeId(reply, id);
        reply.set("error", mapper.valueToTree(new JsonRpcError(code, message, null)));
        return mapper.writeValueAsString(reply);
    }

    private static void writeId(tools.jackson.databind.node.ObjectNode reply, RequestId id) {
        if (id instanceof NumericRequestId number) {
            reply.put("id", number.value());
        } else if (id instanceof StringRequestId string) {
            reply.put("id", string.value());
        } else {
            throw new IllegalArgumentException("Unsupported JSON-RPC request ID");
        }
    }

    public String encode(Object message) {
        return mapper.writeValueAsString(message);
    }

    public JsonRpcResponse decode(
            String json, LongFunction<Class<? extends JsonRpcSuccessResponse<?>>> responseTypeForId) {
        JsonNode message = mapper.readTree(json);
        if (message == null || !message.isObject()) {
            throw new IllegalArgumentException("Expected a JSON object");
        }

        if (message.has("method")) {
            if (message.has("id")) {
                return decodeServerRequest(message);
            }
            return decodeNotification(message);
        }

        Long id = readResponseId(message);
        Class<? extends JsonRpcSuccessResponse<?>> responseType = null;
        if (id != null) {
            responseType = responseTypeForId.apply(id);
        }
        return decodeResponse(message, id, responseType);
    }

    private static Long readResponseId(JsonNode message) {
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

    private JsonRpcResponse decodeResponse(
            JsonNode message,
            Long id,
            Class<? extends JsonRpcSuccessResponse<?>> expectedType
    ) {
        if (message.has("error")) {
            return new ErrorResponse(id, readError(message.get("error")));
        }

        if (expectedType == null) {
            return new UnmatchedResponse(id);
        }

        if (expectedType == ThreadTurnsListResponse.class) {
            return decodeThreadTurnsListResponse(message, id);
        }

        return mapper.treeToValue(message, expectedType);
    }

    /** Decodes a thread history without leaking arbitrary JSON nodes into response DTOs. */
    public ThreadTurnsListResponse readThreadTurnsListResponse(String json) {
        JsonNode message = mapper.readTree(json);
        return decodeThreadTurnsListResponse(message, readResponseId(message));
    }

    private ThreadTurnsListResponse decodeThreadTurnsListResponse(JsonNode message, Long id) {
        JsonNode result = message.path("result");
        JsonNode rawTurns = result.path("data");
        List<ThreadTurnsListResponse.Turn> turns = new ArrayList<>();

        if (rawTurns.isArray()) {
            for (JsonNode rawTurn : rawTurns) {
                List<ThreadTurnsListResponse.Item> items = new ArrayList<>();
                JsonNode rawItems = rawTurn.path("items");
                if (rawItems.isArray()) {
                    for (JsonNode item : rawItems) {
                        List<ThreadTurnsListResponse.UserInput> contents = new ArrayList<>();
                        JsonNode content = item.get("content");
                        if (content != null && content.isArray()) {
                            for (JsonNode input : content) {
                                if (input.isObject()) {
                                    contents.add(mapper.treeToValue(
                                            input, ThreadTurnsListResponse.UserInput.class));
                                }
                            }
                        }
                        items.add(new ThreadTurnsListResponse.Item(
                                optionalString(item, "type"),
                                List.copyOf(contents),
                                optionalString(item, "text")));
                    }
                }
                turns.add(new ThreadTurnsListResponse.Turn(List.copyOf(items)));
            }
        }

        return new ThreadTurnsListResponse(
                id,
                new ThreadTurnsListResponse.Result(
                        List.copyOf(turns),
                        optionalString(result, "nextCursor")));
    }

    private static Notification decodeNotification(JsonNode message) {
        if (message.has("id")) {
            throw new IllegalArgumentException("A notification cannot contain an id");
        }

        String method = readMethod(message);
        JsonNode params = message.get("params");
        NotificationParams.Item item = null;
        NotificationParams.Turn turn = null;

        if (params != null && params.isObject()) {
            JsonNode itemNode = params.get("item");
            if (itemNode != null && itemNode.isObject()) {
                item = new NotificationParams.Item(optionalString(itemNode, "type"));
            }

            JsonNode turnNode = params.get("turn");
            if (turnNode != null && turnNode.isObject()) {
                JsonNode errorNode = turnNode.get("error");
                NotificationParams.Failure failure = errorNode != null && errorNode.isObject()
                        ? new NotificationParams.Failure(optionalString(errorNode, "message"))
                        : null;
                turn = new NotificationParams.Turn(
                        optionalString(turnNode, "id"),
                        optionalString(turnNode, "status"),
                        failure);
            }
        }

        NotificationParams payload = params == null || !params.isObject()
                ? null
                : new NotificationParams(
                        optionalString(params, "threadId"),
                        optionalString(params, "turnId"),
                        optionalString(params, "delta"),
                        item,
                        turn,
                        optionalId(params.get("requestId")));

        return new Notification(NotificationMethod.fromWireName(method), method, payload);
    }

    private static ServerRequest decodeServerRequest(JsonNode message) {
        String method = readMethod(message);
        RequestId requestId = requiredId(message.get("id"), "Server request ID");
        JsonNode params = message.get("params");

        CodexServerRequestParams payload = params == null || !params.isObject()
                ? null
                : new CodexServerRequestParams(
                        optionalString(params, "threadId"),
                        optionalString(params, "turnId"),
                        optionalString(params, "itemId"),
                        optionalString(params, "reason"),
                        optionalString(params, "command"),
                        optionalString(params, "cwd"));

        return new ServerRequest(requestId, ServerRequestMethod.fromWireName(method), method, payload);
    }

    private static String optionalString(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value != null && value.isString() ? value.asString() : null;
    }

    private static RequestId optionalId(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        return requiredId(node, "Request ID");
    }

    private static RequestId requiredId(JsonNode node, String label) {
        if (node != null && node.isString()) {
            return new StringRequestId(node.asString());
        }
        return new NumericRequestId(readLong(node, label));
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

    private JsonRpcError readError(JsonNode error) {
        if (error == null || !error.isObject() || !error.path("message").isString()) {
            throw new IllegalArgumentException("Expected an error object with a message");
        }

        JsonNode dataNode = error.get("data");
        JsonRpcError.Data data = dataNode == null || dataNode.isNull()
                ? null
                : new JsonRpcError.Data(mapper.writeValueAsString(dataNode));

        return new JsonRpcError(
                readLong(error.get("code"), "Error code"),
                error.get("message").stringValue(),
                data);
    }

    /** Parses the generated Codex client request schema at the protocol boundary. */
    public List<String> readClientRequestMethods(String json) {
        JsonNode requests = mapper.readTree(json).path("oneOf");
        if (!requests.isArray() || requests.isEmpty()) {
            throw new IllegalArgumentException("Expected a nonempty oneOf array");
        }

        Set<String> methods = new LinkedHashSet<>();
        for (JsonNode request : requests) {
            JsonNode method = request.path("properties").path("method");
            JsonNode name = method.has("const") ? method.path("const") : method.path("enum").path(0);
            if (!name.isString() || name.asString().isBlank()) {
                throw new IllegalArgumentException("Invalid Codex request method definition");
            }
            methods.add(name.asString());
        }
        return List.copyOf(methods);
    }

    private record UnmatchedResponse(Long id) implements JsonRpcResponse {

        @Override
        public Long getId() {
            return id;
        }
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
            NotificationMethod method, String wireMethod, NotificationParams params
    ) implements JsonRpcResponse {

        @Override
        public Type type() {
            return Type.NOTIFICATION;
        }

        @Override
        public Long getId() {
            return null;
        }
    }

    public record ServerRequest(
            RequestId id, ServerRequestMethod method, String wireMethod,
            CodexServerRequestParams params
    ) implements JsonRpcResponse {

        @Override
        public Type type() {
            return Type.SERVER_REQUEST;
        }

        @Override
        public Long getId() {
            if (id instanceof NumericRequestId numericId) {
                return numericId.value();
            }
            return null;
        }
    }

    public sealed interface RequestId permits NumericRequestId, StringRequestId {
    }

    public record NumericRequestId(long value) implements RequestId {
    }

    public record StringRequestId(String value) implements RequestId {
    }
}
