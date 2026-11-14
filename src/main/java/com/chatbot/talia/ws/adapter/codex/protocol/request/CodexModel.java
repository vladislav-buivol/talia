package com.chatbot.talia.ws.adapter.codex.protocol.request;

import com.fasterxml.jackson.annotation.JsonValue;

public enum CodexModel {
    GPT_5_6_LUNA("gpt-5.6-luna"),
    GPT_5_6_SOL("gpt-5.6-sol");

    private final String id;

    CodexModel(String id) {
        this.id = id;
    }

    @JsonValue
    public String id() {
        return id;
    }

    public static CodexModel fromId(String id) {
        for (CodexModel model : values()) {
            if (model.id.equals(id)) {
                return model;
            }
        }
        return null;
    }
}
