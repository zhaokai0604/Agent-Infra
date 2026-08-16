package com.award.log.controller;

import com.award.log.common.Result;
import com.award.log.security.RequestUserResolver;
import com.award.log.service.impl.RunbookApprovalService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

@Deprecated(since = "delivery-2026-07", forRemoval = false)
@Tag(name = "Runbook Approval", description = "非默认交付面：无挂载 UI / 仅 API，见 docs/deployment/交付API白名单.md")
@RestController
@RequestMapping("/api/runbook")
public class RunbookApprovalController {

    private final RunbookApprovalService runbookApprovalService;
    private final RequestUserResolver requestUserResolver;

    public RunbookApprovalController(RunbookApprovalService runbookApprovalService,
                                     RequestUserResolver requestUserResolver) {
        this.runbookApprovalService = runbookApprovalService;
        this.requestUserResolver = requestUserResolver;
    }

    @PostMapping("/submit")
    public Result<Map<String, Object>> submit(@RequestBody(required = false) Map<String, Object> payload,
                                              HttpServletRequest request) {
        try {
            String requester = currentPrincipal(request);
            Map<String, Object> safePayload = payload == null ? Map.of() : payload;
            @SuppressWarnings("unchecked")
            Map<String, Object> parameters = safePayload.get("parameters") instanceof Map<?, ?> m
                    ? (Map<String, Object>) m
                    : null;
            Map<String, Object> item = runbookApprovalService.submit(
                    stringField(safePayload, "title"),
                    stringField(safePayload, "action"),
                    stringField(safePayload, "command"),
                    stringField(safePayload, "toolName"),
                    parameters,
                    requester
            );
            return Result.success(item);
        } catch (IllegalArgumentException e) {
            return Result.error(400, e.getMessage());
        }
    }

    @PostMapping("/{id}/approve")
    public Result<Map<String, Object>> approve(@PathVariable long id, HttpServletRequest request) {
        if (!requestUserResolver.isAdmin(request)) {
            return Result.error(403, "Only administrators can approve runbooks");
        }
        try {
            return Result.success(runbookApprovalService.approve(id, currentPrincipal(request)));
        } catch (NoSuchElementException e) {
            return Result.error(404, e.getMessage());
        } catch (IllegalStateException e) {
            return Result.error(409, e.getMessage());
        }
    }

    @PostMapping("/{id}/reject")
    public Result<Map<String, Object>> reject(@PathVariable long id,
                                              @RequestBody(required = false) Map<String, String> body,
                                              HttpServletRequest request) {
        if (!requestUserResolver.isAdmin(request)) {
            return Result.error(403, "Only administrators can reject runbooks");
        }
        try {
            String reason = body == null ? "" : body.getOrDefault("reason", "");
            return Result.success(runbookApprovalService.reject(id, currentPrincipal(request), reason));
        } catch (NoSuchElementException e) {
            return Result.error(404, e.getMessage());
        } catch (IllegalStateException e) {
            return Result.error(409, e.getMessage());
        }
    }

    @PostMapping("/{id}/execute")
    public Result<Map<String, Object>> execute(@PathVariable long id, HttpServletRequest request) {
        if (!requestUserResolver.isAdmin(request)) {
            return Result.error(403, "Only administrators can execute approved runbooks");
        }
        try {
            return Result.success(runbookApprovalService.execute(id, currentPrincipal(request)));
        } catch (NoSuchElementException e) {
            return Result.error(404, e.getMessage());
        } catch (IllegalStateException e) {
            return Result.error(409, e.getMessage());
        }
    }

    @GetMapping("/list")
    public Result<List<Map<String, Object>>> list(HttpServletRequest request) {
        String principal = currentPrincipal(request);
        boolean admin = requestUserResolver.isAdmin(request);
        return Result.success(runbookApprovalService.list(principal, admin));
    }

    private String currentPrincipal(HttpServletRequest request) {
        Integer userId = requestUserResolver.currentUserId(request);
        return userId == null ? "unknown" : String.valueOf(userId);
    }

    private static String stringField(Map<String, Object> payload, String key) {
        Object v = payload.get(key);
        return v == null ? null : String.valueOf(v).trim();
    }
}
