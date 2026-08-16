package com.award.log.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;

/**
 * Structured SSE events for assistant streams (decoupled from Markdown body).
 */
public final class AssistantStreamEvents {

    public static final String PREFIX = "ASSISTANT_EVENT:";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AssistantStreamEvents() {
    }

    public static String encode(Map<String, Object> event) {
        if (event == null || event.isEmpty()) {
            return null;
        }
        try {
            return PREFIX + MAPPER.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            return null;
        }
    }
}
