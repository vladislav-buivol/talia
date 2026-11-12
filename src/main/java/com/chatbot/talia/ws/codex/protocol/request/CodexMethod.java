package com.chatbot.talia.ws.codex.protocol.request;

import com.fasterxml.jackson.annotation.JsonValue;


public enum CodexMethod {

    INITIALIZE("initialize"),
    INITIALIZED("initialized"),
    THREAD_START("thread/start"),
    TURN_START("turn/start");

    private final String value;

    CodexMethod(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }
}
