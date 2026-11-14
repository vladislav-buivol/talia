package com.chatbot.talia.ws.core.protocol.request;

public record GetHistoryRequest(Integer limit) implements ChatActionGetRequest {
}
