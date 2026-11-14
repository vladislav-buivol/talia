package com.chatbot.talia.ws.adapter.codex.protocol.request;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ThreadDeleteRequest(Long id, Params params) implements CodexRequest<ThreadDeleteRequest.Params> {
    public static ThreadDeleteRequest create(String threadId) {
        return new ThreadDeleteRequest(null, new Params(threadId));
    }

    @Override
    public ThreadDeleteRequest withId(long id) {
        return new ThreadDeleteRequest(id, params);
    }

    @Override
    @JsonProperty(value = "method", access = JsonProperty.Access.READ_ONLY)
    public String method() {
        return CodexMethod.THREAD_DELETE.value();
    }

    public record Params(String threadId) {
    }
}
