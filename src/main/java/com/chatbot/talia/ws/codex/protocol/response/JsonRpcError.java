package com.chatbot.talia.ws.codex.protocol.response;

import tools.jackson.databind.JsonNode;

public record JsonRpcError(long code, String message, JsonNode data) {
}
