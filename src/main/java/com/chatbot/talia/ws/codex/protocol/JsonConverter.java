package com.chatbot.talia.ws.codex.protocol;

import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class JsonConverter {

    private final ObjectMapper objectMapper;

    public JsonConverter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String toJson(Object value) {
        return objectMapper.writeValueAsString(value);
    }

    public <T> T fromJson(String json, Class<T> type) {
        return objectMapper.readValue(json, type);
    }
}