package com.chatbot.talia.ws.codex.client;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class CodexRpcSchema {
    private CodexRpcSchema() {
    }

    /** Lists the request methods declared in Codex's generated ClientRequest.json. */
    public static List<String> getAvailableActions(Path clientRequestSchema) throws IOException {
        JsonNode requests = new ObjectMapper().readTree(Files.readString(clientRequestSchema)).path("oneOf");
        if (!requests.isArray() || requests.isEmpty()) {
            throw new IOException("Expected request definitions in " + clientRequestSchema);
        }

        Set<String> methods = new LinkedHashSet<>();
        for (JsonNode request : requests) {
            JsonNode method = request.path("properties").path("method");
            JsonNode name = method.has("const") ? method.path("const") : method.path("enum").path(0);
            if (!name.isString() || name.asString().isBlank()) {
                throw new IOException("Invalid request method definition in " + clientRequestSchema);
            }
            methods.add(name.asString());
        }
        return List.copyOf(methods);
    }
}
