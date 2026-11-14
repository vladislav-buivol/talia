package com.chatbot.talia.ws.adapter.codex.protocol.response;

import tools.jackson.databind.JsonNode;

public record JsonRpcError(long code, String message, JsonNode data) {
}
