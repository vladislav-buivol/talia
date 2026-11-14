package com.chatbot.talia.ws.adapter.codex.protocol.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ThreadDeleteResponse(Long id, Result result) implements JsonRpcSuccessResponse<ThreadDeleteResponse.Result> {
    @Override
    public Long getId() {
        return id;
    }

    @Override
    public Result getResult() {
        return result;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Result() {
    }
}
