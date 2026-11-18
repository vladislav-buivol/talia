package com.chatbot.talia.ws.adapter.codex.protocol.response;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import tools.jackson.databind.JsonNode;
@JsonIgnoreProperties(ignoreUnknown = true)
public record TurnInterruptResponse(Long id, JsonNode result) implements JsonRpcSuccessResponse<JsonNode> {
    @Override public Long getId() { return id; }
    @Override public JsonNode getResult() { return result; }
}
