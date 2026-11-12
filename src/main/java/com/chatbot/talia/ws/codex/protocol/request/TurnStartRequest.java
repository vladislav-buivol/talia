package com.chatbot.talia.ws.codex.protocol.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record TurnStartRequest(Long id, Params params)
        implements CodexRequest<TurnStartRequest.Params> {

    public static TurnStartRequest create(Long id, String threadId, String text) {
        return new TurnStartRequest(id, new Params(threadId, List.of(new TextInput(text))));
    }

    @Override
    @JsonProperty(value = "method", access = JsonProperty.Access.READ_ONLY)
    public String method() {
        return CodexMethod.TURN_START.value();
    }

    public record Params(String threadId, List<TextInput> input) {
    }

    public record TextInput(String text) {
        @JsonProperty(value = "type", access = JsonProperty.Access.READ_ONLY)
        public String type() {
            return "text";
        }
    }
}
