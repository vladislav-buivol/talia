package com.chatbot.talia.ws.core.protocol.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.chatbot.talia.ws.core.protocol.ChatAction;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChatDialog(String threadId, String name, String preview, List<ChatAction> availableAction) {
}
