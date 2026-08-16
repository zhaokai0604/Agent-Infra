package com.award.log.controller;

import com.award.log.analysis.AiDiagnosisContextBuilder;
import com.award.log.security.RequestUserResolver;
import com.award.log.service.AiDiagnosisService;
import com.award.log.task.AnalysisTaskManager;
import com.award.log.task.TaskInfo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.util.Map;

import static org.springframework.http.MediaType.TEXT_EVENT_STREAM_VALUE;

@Slf4j
@Tag(name = "AI Diagnosis Stream")
@RestController
@RequestMapping("/log/diagnose")
public class AiDiagnosisController {

    @Autowired
    private AiDiagnosisService aiDiagnosisService;

    @Autowired
    private AnalysisTaskManager taskManager;

    @Autowired
    private RequestUserResolver requestUserResolver;

    @Autowired
    @Qualifier("sseTaskExecutor")
    private ThreadPoolTaskExecutor sseExecutor;

    @Operation(summary = "General AI ops chat stream")
    @PostMapping(value = "/chat", produces = TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@RequestBody(required = false) Map<String, String> payload) {
        if (payload == null) {
            return shortMessageEmitter("Request body cannot be empty");
        }
        String rawMessage = payload.getOrDefault("message", "").trim();
        if (rawMessage.isBlank()) {
            return shortMessageEmitter("Request message cannot be empty");
        }
        final String message = rawMessage.length() > AiDiagnosisContextBuilder.CHAT_USER_MESSAGE_MAX_CHARS
                ? rawMessage.substring(0, AiDiagnosisContextBuilder.CHAT_USER_MESSAGE_MAX_CHARS)
                : rawMessage;
        SseEmitter emitter = new SseEmitter(180000L);
        sseExecutor.execute(() -> {
            try {
                Flux<String> stream = aiDiagnosisService.chatStream(message);
                if (stream == null) {
                    emitter.send("AI 服务暂不可用");
                    emitter.complete();
                    return;
                }
                stream.doOnNext(chunk -> {
                            try {
                                if (chunk != null) {
                                    emitter.send(chunk);
                                }
                            } catch (Exception e) {
                                log.error("Failed to send SSE chunk", e);
                                emitter.completeWithError(e);
                            }
                        })
                        .doOnComplete(emitter::complete)
                        .doOnError(e -> {
                            log.error("AI chat stream failed", e);
                            emitter.completeWithError(e);
                        })
                        .subscribe();
            } catch (Exception e) {
                log.error("Execute AI chat stream failed", e);
                emitter.completeWithError(e);
            }
        });
        return emitter;
    }

    @Operation(summary = "Run AI diagnosis stream")
    @GetMapping(value = "/stream/{taskId}", produces = TEXT_EVENT_STREAM_VALUE)
    public SseEmitter performDiagnosisStream(HttpServletRequest request, @PathVariable String taskId) {
        SseEmitter emitter = new SseEmitter(180000L);
        try {
            Integer userId = requestUserResolver.currentUserId(request);
            boolean admin = requestUserResolver.isAdmin(request);
            TaskInfo task = taskManager.getTaskForUser(taskId, userId, admin);
            if (task == null) {
                emitter.send("Task not found or access denied");
                emitter.complete();
                return emitter;
            }
            if (task.getResult() == null) {
                emitter.send("Analysis has not completed yet. Current status: " + task.getStatus());
                emitter.complete();
                return emitter;
            }
            sseExecutor.execute(() -> {
                StringBuilder fullResponse = new StringBuilder();
                try {
                    Flux<String> stream = aiDiagnosisService.generateDiagnosisStreamFromFullResult(task.getResult());
                    if (stream == null) {
                        emitter.send("Diagnosis service unavailable");
                        emitter.complete();
                        return;
                    }
                    stream.doOnNext(chunk -> {
                                try {
                                    if (chunk != null) {
                                        emitter.send(chunk);
                                        fullResponse.append(chunk);
                                    }
                                } catch (Exception e) {
                                    log.error("Failed to send diagnosis stream chunk", e);
                                    emitter.complete();
                                }
                            })
                            .doOnComplete(() -> {
                                taskManager.updateAiDiagnosis(taskId, fullResponse.toString());
                                emitter.complete();
                            })
                            .doOnError(e -> {
                                log.error("Diagnosis stream failed for task {}", taskId, e);
                                try {
                                    emitter.send("Diagnosis failed");
                                } catch (Exception ignored) {
                                }
                                emitter.complete();
                            })
                            .subscribe();
                } catch (Exception e) {
                    log.error("Execute diagnosis stream failed for task {}", taskId, e);
                    try {
                        emitter.send("Diagnosis execution failed");
                    } catch (Exception ignored) {
                    }
                    emitter.complete();
                }
            });
        } catch (Exception e) {
            log.error("Prepare diagnosis stream failed for task {}", taskId, e);
            try {
                emitter.send("Diagnosis request failed");
            } catch (Exception ignored) {
            }
            emitter.complete();
        }
        return emitter;
    }

    private static SseEmitter shortMessageEmitter(String message) {
        SseEmitter emitter = new SseEmitter(1000L);
        try {
            emitter.send(message);
            emitter.complete();
        } catch (Exception ignored) {
        }
        return emitter;
    }
}
