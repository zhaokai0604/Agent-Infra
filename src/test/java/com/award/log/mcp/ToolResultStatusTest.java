package com.award.log.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolResultStatusTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void warnFactorySerializesStatusWarn() throws Exception {
        String json = mapper.writeValueAsString(ToolResult.warn("{\"filesDeleted\":0}", 1500L));
        assertTrue(json.contains("\"status\":\"WARN\""));
        assertTrue(json.contains("\"success\":true"));
        assertEquals("WARN", McpToolPayloadParser.statusOf(mapper, json));
    }

    @Test
    void successAndErrorDefaultStatus() throws Exception {
        String ok = mapper.writeValueAsString(ToolResult.success("{}", 10L));
        assertEquals("SUCCESS", McpToolPayloadParser.statusOf(mapper, ok));
        String err = mapper.writeValueAsString(ToolResult.error("boom", 10L));
        assertEquals("ERROR", McpToolPayloadParser.statusOf(mapper, err));
    }
}
