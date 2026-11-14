package com.chatbot.talia.ws.core.protocol.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.chatbot.talia.ws.core.protocol.ChatAction;
import com.chatbot.talia.ws.core.protocol.ChatResponse;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChatEvent(String type, List<ChatAction> availableAction, Object data) implements ChatResponse {
}
