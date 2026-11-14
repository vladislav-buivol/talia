package com.chatbot.talia.ws.core.protocol.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChatDialogPage(List<ChatDialog> dialogs, String nextCursor) {
}
