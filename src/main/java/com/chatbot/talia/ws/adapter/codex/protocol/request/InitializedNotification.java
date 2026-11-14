package com.chatbot.talia.ws.adapter.codex.protocol.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

public record InitializedNotification(Map<String, Object> params)
        implements CodexNotification<Map<String, Object>> {

    public static InitializedNotification create() {
        return new InitializedNotification(Map.of());
    }

    @Override
    @JsonProperty(value = "method", access = JsonProperty.Access.READ_ONLY)
    public String method() {
        return CodexMethod.INITIALIZED.value();
    }
}
