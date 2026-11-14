package com.chatbot.talia.ws.adapter.codex.protocol.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ThreadUnsubscribeResponse(Long id, Result result)
        implements JsonRpcSuccessResponse<ThreadUnsubscribeResponse.Result> {
    @Override
    public Long getId() {
        return id;
    }

    @Override
    public Result getResult() {
        return result;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Result(String status) {
    }
}
