package com.chatbot.talia.ws.adapter.codex.protocol.request;

import com.fasterxml.jackson.annotation.JsonProperty;
public record InitializedNotification(Params params)
        implements CodexNotification<InitializedNotification.Params> {

    public static InitializedNotification create() {
        return new InitializedNotification(new Params());
    }

    @Override
    @JsonProperty(value = "method", access = JsonProperty.Access.READ_ONLY)
    public String method() {
        return CodexMethod.INITIALIZED.value();
    }

    public record Params() {
    }
}
