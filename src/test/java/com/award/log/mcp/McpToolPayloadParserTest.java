package com.award.log.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpToolPayloadParserTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void parsePayload_unwrapsToolResultEnvelope() throws Exception {
        String raw = mapper.writeValueAsString(ToolResult.success("[{\"usePercent\":\"72%\"}]", 10L));
        JsonNode data = McpToolPayloadParser.parsePayload(mapper, raw);
        assertNotNull(data);
        assertTrue(data.isArray());
        assertEquals("72%", data.get(0).path("usePercent").asText());
    }

    @Test
    void parsePayload_acceptsPlainDiskAnalyzeJson() throws Exception {
        String raw = """
                {"overview":[{"mountedOn":"C:","usePercent":"72%"}],"hint":"ok","durationMs":12}
                """;
        JsonNode data = McpToolPayloadParser.parsePayload(mapper, raw);
        assertNotNull(data);
        assertEquals("ok", data.path("hint").asText());
        assertEquals("72%", data.path("overview").get(0).path("usePercent").asText());
    }
}
