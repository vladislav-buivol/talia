package com.chatbot.talia.ws.adapter.codex.protocol;

import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class CodexRpcSchema {
    private CodexRpcSchema() {
    }

    /** Reads methods declared in Codex ClientRequest.json using the shared codec. */
    public static List<String> getAvailableActions(Path clientRequestSchema) throws IOException {
        String content = Files.readString(clientRequestSchema);
        try {
            return new CodexMessageCodec(new ObjectMapper()).readClientRequestMethods(content);
        } catch (IllegalArgumentException error) {
            throw new IOException("Invalid Codex request schema: " + clientRequestSchema, error);
        }
    }
}
