package com.chatbot.talia.ws.codex.protocol.request;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ThreadStartRequest(Long id, Params params)
        implements CodexRequest<ThreadStartRequest.Params> {

    public static ThreadStartRequest create(Long id, String model) {
        return new ThreadStartRequest(id, new Params(model));
    }

    @Override
    @JsonProperty(value = "method", access = JsonProperty.Access.READ_ONLY)
    public String method() {
        return CodexMethod.THREAD_START.value();
    }

    public record Params(String model) {
    }
}
