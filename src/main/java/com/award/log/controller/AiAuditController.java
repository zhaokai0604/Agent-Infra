package com.award.log.controller;

import com.award.log.common.Result;
import com.award.log.security.RequestUserResolver;
import com.award.log.service.impl.AiAuditLogService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/admin/audit/ai")
public class AiAuditController {

    private final AiAuditLogService aiAuditLogService;
    private final RequestUserResolver requestUserResolver;
    private final boolean aiAuditRelaxedRead;

    public AiAuditController(
            AiAuditLogService aiAuditLogService,
            RequestUserResolver requestUserResolver,
            @Value("${app.security.ai-audit-relaxed-read:false}") boolean aiAuditRelaxedRead) {
        this.aiAuditLogService = aiAuditLogService;
        this.requestUserResolver = requestUserResolver;
        this.aiAuditRelaxedRead = aiAuditRelaxedRead;
    }

    @GetMapping("/recent")
    public Result<List<Map<String, Object>>> recent(@RequestParam(defaultValue = "100") int limit,
                                                    HttpServletRequest request) {
        if (!this.aiAuditRelaxedRead && !requestUserResolver.isAdmin(request)) {
            return Result.error(403, "仅管理员可查看AI审计日志");
        }
        return Result.success(aiAuditLogService.listRecent(limit));
    }
}
