package com.chatbot.talia.ws.core;

import com.chatbot.talia.ws.core.protocol.ChatAction;
import com.chatbot.talia.ws.core.protocol.ChatRequest;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.lang.reflect.Field;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class ChatServiceHandlerRegistryTests {

    @Test
    void everyDeclaredRequestHasRegisteredHandler() throws Exception {
        try (ChatService service = new ChatService(new ObjectMapper(), mock(ChatBackend.class))) {
            Field field = ChatService.class.getDeclaredField("handlers");
            field.setAccessible(true);
            Object registered = field.get(service);
            Map<?, ?> handlers = (Map<?, ?>) registered;

            JsonSubTypes subtypes = ChatRequest.class.getAnnotation(JsonSubTypes.class);
            assertNotNull(subtypes);
            assertEquals(ChatAction.values().length, handlers.size());

            for (JsonSubTypes.Type subtype : subtypes.value()) {
                assertTrue(handlers.containsKey(subtype.value()),
                        "Missing handler for " + subtype.value().getSimpleName());
            }
        }
    }
}
