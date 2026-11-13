package com.chatbot.talia.ws.codex.protocol.request;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ThreadUnsubscribeRequest(Long id, Params params)
        implements CodexRequest<ThreadUnsubscribeRequest.Params> {
    public static ThreadUnsubscribeRequest create(String threadId) {
        return new ThreadUnsubscribeRequest(null, new Params(threadId));
    }

    @Override
    public ThreadUnsubscribeRequest withId(long id) {
        return new ThreadUnsubscribeRequest(id, params);
    }

    @Override
    @JsonProperty(value = "method", access = JsonProperty.Access.READ_ONLY)
    public String method() {
        return CodexMethod.THREAD_UNSUBSCRIBE.value();
    }

    public record Params(String threadId) {
    }
}
