package com.chatbot.talia.ws.adapter.codex.protocol.request;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ThreadResumeRequest(Long id, Params params)
        implements CodexRequest<ThreadResumeRequest.Params> {
    public static ThreadResumeRequest create(String threadId) {
        return new ThreadResumeRequest(null, new Params(threadId, true));
    }

    @Override
    public ThreadResumeRequest withId(long id) {
        return new ThreadResumeRequest(id, params);
    }

    @Override
    @JsonProperty(value = "method", access = JsonProperty.Access.READ_ONLY)
    public String method() {
        return CodexMethod.THREAD_RESUME.value();
    }

    public record Params(String threadId, boolean excludeTurns) {
    }
}
