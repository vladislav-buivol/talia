package com.chatbot.talia.ws.codex.protocol.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TurnStartResponse(Long id, Result result)
        implements JsonRpcSuccessResponse<TurnStartResponse.Result> {

    @Override
    public Long getId() {
        return id;
    }

    @Override
    public Result getResult() {
        return result;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Result(TurnInfo turn) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TurnInfo(String id, String status) {
    }
}
