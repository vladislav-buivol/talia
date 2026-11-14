package com.chatbot.talia.ws.core.protocol;

import java.util.List;

public interface ChatResponse {
    List<ChatAction> availableAction();
}
