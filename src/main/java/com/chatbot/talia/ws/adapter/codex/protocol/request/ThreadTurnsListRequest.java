package com.chatbot.talia.ws.adapter.codex.protocol.request;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ThreadTurnsListRequest(Long id, Params params)
        implements CodexRequest<ThreadTurnsListRequest.Params> {
    public static ThreadTurnsListRequest create(String threadId, String cursor) {
        return new ThreadTurnsListRequest(null, new Params(threadId, cursor, 1, "asc", "full"));
    }

    @Override
    public ThreadTurnsListRequest withId(long id) {
        return new ThreadTurnsListRequest(id, params);
    }

    @Override
    @JsonProperty(value = "method", access = JsonProperty.Access.READ_ONLY)
    public String method() {
        return CodexMethod.THREAD_TURNS_LIST.value();
    }

    public record Params(String threadId, String cursor, int limit, String sortDirection, String itemsView) {
    }
}
