package com.award.log.controller;

import com.award.log.common.Result;
import com.award.log.service.AiAnalysisService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

@Deprecated(since = "delivery-2026-07", forRemoval = false)
@Tag(name = "LLM Chat", description = "非默认交付面：无挂载 UI / 仅 API，见 docs/deployment/交付API白名单.md")
@RestController
@RequestMapping("/api/v1/llm")
public class LlmChatController {

    private final AiAnalysisService aiAnalysisService;

    public LlmChatController(AiAnalysisService aiAnalysisService) {
        this.aiAnalysisService = aiAnalysisService;
    }

    @Operation(summary = "流式对话与工具调用")
    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@RequestBody(required = false) Map<String, Object> body) {
        String prompt = extractPrompt(body);
        SseEmitter emitter = new SseEmitter(60000L);
        Thread worker = new Thread(() -> {
            try {
                String result = normalizeAnswer(aiAnalysisService.analyzeLog(prompt));
                result.codePoints().forEach(codePoint -> {
                    try {
                        emitter.send(SseEmitter.event()
                                .name("token")
                                .data(new String(Character.toChars(codePoint))));
                    } catch (IOException e) {
                        throw new IllegalStateException(e);
                    }
                });
                Map<String, Object> done = new LinkedHashMap<>();
                done.put("completed", true);
                done.put("empty", result.isEmpty());
                emitter.send(SseEmitter.event().name("done").data(done));
                emitter.complete();
            } catch (IllegalStateException e) {
                if (e.getCause() instanceof IOException ioException) {
                    emitter.completeWithError(ioException);
                    return;
                }
                emitter.completeWithError(e);
            } catch (Exception e) {
                emitter.completeWithError(e);
            }
        }, "llm-sse-thread");
        worker.setDaemon(true);
        worker.start();
        return emitter;
    }

    @Operation(summary = "非流式对话")
    @PostMapping("/chat-sync")
    public Result<Map<String, Object>> chatSync(@RequestBody(required = false) Map<String, Object> body) {
        String prompt = extractPrompt(body);
        String answer = normalizeAnswer(aiAnalysisService.analyzeLog(prompt));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("answer", answer);
        payload.put("empty", answer.isEmpty());
        return Result.success(payload);
    }

    private static String extractPrompt(Map<String, Object> body) {
        if (body == null) {
            return "";
        }
        Object prompt = body.get("prompt");
        return prompt == null ? "" : String.valueOf(prompt);
    }

    private static String normalizeAnswer(String answer) {
        return answer == null ? "" : answer;
    }
}
