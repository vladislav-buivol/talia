package com.chatbot.talia.ws.adapter.codex.protocol.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ThreadResumeResponse(Long id, Result result)
        implements JsonRpcSuccessResponse<ThreadResumeResponse.Result> {
    @Override
    public Long getId() {
        return id;
    }

    @Override
    public Result getResult() {
        return result;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Result(ThreadInfo thread) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ThreadInfo(String id) {
    }
}
