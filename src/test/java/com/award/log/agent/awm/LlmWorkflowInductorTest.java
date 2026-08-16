package com.award.log.agent.awm;

import com.award.log.util.TestTraceIdGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmWorkflowInductorTest {

    private final LlmWorkflowInductor inductor = new LlmWorkflowInductor(
            new ObjectMapper(), new TestTraceIdGenerator("llm-trace-12345678"));

    @Test
    void parseValidJsonWorkflow() {
        String json = """
                {
                  "title": "disk cleanup",
                  "description": "inspect and cleanup",
                  "steps": [
                    {"envDesc": "disk high", "reason": "collect usage", "toolName": "DiskTool", "argsTemplate": {}},
                    {"envDesc": "temp hotspot", "reason": "preview cleanup", "toolName": "CleanTempTool",
                     "argsTemplate": {"path": "{temp-path}", "days": "7", "dryRun": "{dryRun}"}}
                  ]
                }
                """;
        Optional<OpsWorkflow> wf = inductor.parseAndValidate(json, "trace-1", "disk", List.of("DISK_PRESSURE"));
        assertTrue(wf.isPresent());
        assertEquals("llm", wf.get().sourceType());
        assertEquals(2, wf.get().steps().size());
        assertEquals("CleanTempTool", wf.get().steps().get(1).toolName());
    }

    @Test
    void rejectsUnknownTool() {
        String json = """
                {"title":"x","description":"y","steps":[
                  {"envDesc":"a","reason":"b","toolName":"UnknownTool","argsTemplate":{}},
                  {"envDesc":"c","reason":"d","toolName":"DiskTool","argsTemplate":{}}
                ]}
                """;
        assertTrue(inductor.parseAndValidate(json, "t", "disk", List.of()).isEmpty());
    }

    @Test
    void rejectsUnsupportedPrivilegedTool() {
        String json = """
                {"title":"x","description":"y","steps":[
                  {"envDesc":"a","reason":"b","toolName":"PrivilegeTool","argsTemplate":{}},
                  {"envDesc":"c","reason":"d","toolName":"DiskTool","argsTemplate":{}}
                ]}
                """;
        assertTrue(inductor.parseAndValidate(json, "t", "disk", List.of()).isEmpty());
    }

    @Test
    void rejectsLiteralPathInArgs() {
        String json = """
                {"title":"x","description":"y","steps":[
                  {"envDesc":"a","reason":"b","toolName":"DiskTool","argsTemplate":{}},
                  {"envDesc":"c","reason":"d","toolName":"CleanTempTool",
                   "argsTemplate":{"path":"/etc/passwd","dryRun":"true"}}
                ]}
                """;
        assertTrue(inductor.parseAndValidate(json, "t", "disk", List.of()).isEmpty());
    }

    @Test
    void rejectsNonMapArgsTemplate() {
        String json = """
                {"title":"x","description":"y","steps":[
                  {"envDesc":"a","reason":"b","toolName":"DiskTool","argsTemplate":{}},
                  {"envDesc":"c","reason":"d","toolName":"CleanTempTool","argsTemplate":"oops"}
                ]}
                """;
        assertTrue(inductor.parseAndValidate(json, "t", "disk", List.of()).isEmpty());
    }

    @Test
    void extractJsonFromFence() {
        String raw = "desc\n```json\n{\"title\":\"t\"}\n```";
        assertEquals("{\"title\":\"t\"}", LlmWorkflowInductor.extractJsonBlock(raw));
    }

    @Test
    void extractJsonFallsBackToRawJsonBody() {
        String raw = "prefix {\"title\":\"t\",\"description\":\"d\",\"steps\":[]} suffix";
        assertTrue(LlmWorkflowInductor.extractJsonBlock(raw).startsWith("{\"title\""));
    }

    @Test
    void isAvailableRequiresFeatureFlagAndChatModel() {
        LlmWorkflowInductor disabled = new LlmWorkflowInductor(
                new ObjectMapper(), new TestTraceIdGenerator("llm-a"));
        assertFalse(disabled.isAvailable());

        disabled.setChatModel(prompt -> new ChatResponse(List.of(
                new Generation(new AssistantMessage("{}")))));
        assertFalse(disabled.isAvailable());

        ReflectionTestUtils.setField(disabled, "enabled", true);
        assertTrue(disabled.isAvailable());
    }

    @Test
    void induceReturnsEmptyWhenTrajectoryMissing() {
        ReflectionTestUtils.setField(inductor, "enabled", true);
        inductor.setChatModel(prompt -> new ChatResponse(List.of(
                new Generation(new AssistantMessage("{}")))));

        Optional<OpsWorkflow> result = inductor.induce(
                new OpsExperience("t", "msg", "ASSISTANT", "AssistantOrchestrator", "EXECUTED", true,
                        null, List.of(), 0L, null),
                "disk",
                List.of("DISK_PRESSURE"));

        assertTrue(result.isEmpty());
    }

    @Test
    void induceBuildsWorkflowFromChatModelResponse() {
        LlmWorkflowInductor enabledInductor = new LlmWorkflowInductor(
                new ObjectMapper(), new TestTraceIdGenerator("llm-trace-87654321"));
        ReflectionTestUtils.setField(enabledInductor, "enabled", true);
        enabledInductor.setChatModel(new CapturingChatModel("""
                ```json
                {"title":"cpu fix","description":"restart busy service","steps":[
                  {"envDesc":"","reason":"","toolName":"SystemLoadTool","argsTemplate":{}},
                  {"envDesc":"cpu high","reason":"restart service","toolName":"ServiceRestartTool","argsTemplate":{"serviceName":"{service-name}","dryRun":"{dryRun}"}}
                ]}
                ```
                """));

        Optional<OpsWorkflow> result = enabledInductor.induce(
                new OpsExperience(
                        "trace-llm",
                        "restart nginx",
                        "ASSISTANT",
                        "AssistantOrchestrator",
                        "EXECUTED",
                        true,
                        null,
                        List.of(
                                Map.of("phase", "preview", "toolName", "SystemLoadTool", "detail", "load snapshot"),
                                Map.of("phase", "execute", "toolName", "ServiceRestartTool",
                                        "parameters", Map.of("serviceName", "nginx"))
                        ),
                        30L,
                        null
                ),
                "cpu",
                List.of("CPU_HIGH"));

        assertTrue(result.isPresent());
        assertEquals("llm", result.get().sourceType());
        assertEquals("ServiceRestartTool", result.get().steps().get(1).toolName());
    }

    private static final class CapturingChatModel implements ChatModel {
        private final String text;

        private CapturingChatModel(String text) {
            this.text = text;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            boolean sawUserPrompt = prompt.getInstructions().stream()
                    .anyMatch(m -> m instanceof UserMessage && ((UserMessage) m).getText().contains("domain=cpu"));
            if (!sawUserPrompt) {
                throw new AssertionError("expected user prompt");
            }
            return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
        }
    }
}
