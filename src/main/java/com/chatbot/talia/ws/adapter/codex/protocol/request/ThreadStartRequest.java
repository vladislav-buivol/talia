package com.chatbot.talia.ws.adapter.codex.protocol.request;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ThreadStartRequest(Long id, Params params)
        implements CodexRequest<ThreadStartRequest.Params> {
    public static ThreadStartRequest create(CodexModel model) {
        return create(null, model);
    }


    public static ThreadStartRequest create(Long id, CodexModel model) {
        return new ThreadStartRequest(id, new Params(model));
    }

    @Override
    public ThreadStartRequest withId(long id) {
        return new ThreadStartRequest(id, params);
    }

    @Override
    @JsonProperty(value = "method", access = JsonProperty.Access.READ_ONLY)
    public String method() {
        return CodexMethod.THREAD_START.value();
    }

    public record Params(CodexModel model) {
    }
}
