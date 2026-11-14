package com.chatbot.talia.ws.adapter.codex.protocol.request;

import com.fasterxml.jackson.annotation.JsonProperty;

public record InitializeRequest(Long id, Params params) implements CodexRequest<InitializeRequest.Params> {

    public static InitializeRequest create(String name, String title, String version) {
        return create(null, name, title, version);
    }

    public static InitializeRequest create(Long id, String name, String title, String version) {
        ClientInfo clientInfo = new ClientInfo(name, title, version);
        Params params = new Params(clientInfo);
        return new InitializeRequest(id, params);
    }

    @Override
    public InitializeRequest withId(long id) {
        return new InitializeRequest(id, params);
    }

    @Override
    @JsonProperty(value = "method", access = JsonProperty.Access.READ_ONLY)
    public String method() {
        return CodexMethod.INITIALIZE.value();
    }


    public record Params(ClientInfo clientInfo) {
    }

    public record ClientInfo(
            String name,
            String title,
            String version
    ) {
    }
}
