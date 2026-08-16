package com.award.log.controller;

import com.award.log.common.Result;
import com.award.log.service.SecuritySelfCheckService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@Tag(name = "安全自检")
@RestController
@RequestMapping("/api/security")
public class SecuritySelfCheckController {

    private final SecuritySelfCheckService securitySelfCheckService;

    public SecuritySelfCheckController(SecuritySelfCheckService securitySelfCheckService) {
        this.securitySelfCheckService = securitySelfCheckService;
    }

    @Operation(summary = "一键安全自检（探针调用真实安全门，不执行系统命令）")
    @GetMapping("/self-check")
    public Result<Map<String, Object>> selfCheck() {
        return Result.success(securitySelfCheckService.run());
    }
}
