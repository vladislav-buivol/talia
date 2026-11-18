package com.chatbot.talia.ws.adapter.codex.protocol.request;
import com.fasterxml.jackson.annotation.JsonProperty;
public record TurnInterruptRequest(Long id, Params params) implements CodexRequest<TurnInterruptRequest.Params> {
    public static TurnInterruptRequest create(String threadId, String turnId) {
        return new TurnInterruptRequest(null, new Params(threadId, turnId));
    }
    @Override public TurnInterruptRequest withId(long id) { return new TurnInterruptRequest(id, params); }
    @Override @JsonProperty(value="method", access=JsonProperty.Access.READ_ONLY)
    public String method() { return CodexMethod.TURN_INTERRUPT.value(); }
    public record Params(String threadId, String turnId) {}
}
