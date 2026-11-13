package com.chatbot.talia.ws.codex.protocol.request;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record ThreadListRequest(Long id, Params params)
        implements CodexRequest<ThreadListRequest.Params> {
    public static ThreadListRequest create(int limit) {
        return new ThreadListRequest(null, new Params(limit, "updated_at", "desc", List.of(), List.of("cli", "vscode", "appServer", "exec")));
    }

    public static ThreadListRequest create(int limit, String cursor) {
        Params defaults = create(limit).params();
        return new ThreadListRequest(null, new Params(limit, defaults.sortKey(), defaults.sortDirection(),
                defaults.modelProviders(), defaults.sourceKinds(), cursor));
    }

    @Override
    public ThreadListRequest withId(long id) {
        return new ThreadListRequest(id, params);
    }

    @Override
    @JsonProperty(value = "method", access = JsonProperty.Access.READ_ONLY)
    public String method() {
        return CodexMethod.THREAD_LIST.value();
    }

    public record Params(int limit, String sortKey, String sortDirection,
                         List<String> modelProviders, List<String> sourceKinds,
                         @JsonInclude(JsonInclude.Include.NON_NULL) String cursor) {
        public Params(int limit, String sortKey, String sortDirection,
                      List<String> modelProviders, List<String> sourceKinds) {
            this(limit, sortKey, sortDirection, modelProviders, sourceKinds, null);
        }
    }
}
