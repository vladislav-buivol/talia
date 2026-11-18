package com.chatbot.talia.ws.adapter.codex.protocol.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ThreadTurnsListResponse(Long id, Result result)
        implements JsonRpcSuccessResponse<ThreadTurnsListResponse.Result> {

    @Override
    public Long getId() {
        return id;
    }

    @Override
    public Result getResult() {
        return result;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Result(List<Turn> data, String nextCursor) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Turn(List<Item> items) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Item(String type, List<UserInput> content, String text) {
        public Item(String type, List<UserInput> content) {
            this(type, content, null);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record UserInput(String type, String text, String path, String url) {
    }
}
