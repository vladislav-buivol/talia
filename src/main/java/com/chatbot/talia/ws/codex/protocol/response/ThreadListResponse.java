package com.chatbot.talia.ws.codex.protocol.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ThreadListResponse(Long id, Result result)
        implements JsonRpcSuccessResponse<ThreadListResponse.Result> {
    @Override
    public Long getId() {
        return id;
    }

    @Override
    public Result getResult() {
        return result;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Result(List<ThreadInfo> data, String nextCursor) {
        public Result(List<ThreadInfo> data) {
            this(data, null);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ThreadInfo(String id, String name, String preview, Long updatedAt) {
        public ThreadInfo(String id) {
            this(id, null, null, null);
        }
    }
}
