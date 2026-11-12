package com.chatbot.talia.ws.codex.protocol.response;

public record InitializeResponse(
        Long id,
        InitializeResult result
) implements JsonRpcSuccessResponse<InitializeResponse.InitializeResult> {

    @Override
    public Long getId() {
        return id;
    }

    @Override
    public InitializeResult getResult() {
        return result;
    }

    public record InitializeResult(
            String userAgent,
            String codexHome,
            String platformFamily,
            String platformOs
    ) {
    }
}