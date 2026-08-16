package com.award.log.security;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class HighRiskCommandRule {
    private String id;
    private String name;
    private String description;
    private String ruleExpression;
    private boolean enabled;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static HighRiskCommandRule createDefaultRule() {
        return HighRiskCommandRule.builder()
                .id("DEFAULT_HIGH_RISK_COMMAND_RULE")
                .name("默认高危命令检测规则（扩展 DSL，默认关闭）")
                .description("内置正则已覆盖常见高危命令；启用本规则前请确认 DSL 支持 CONTAINS 等语法")
                .ruleExpression(
                        "(COMMAND CONTAINS 'RM' AND COMMAND CONTAINS '-RF' AND (COMMAND CONTAINS '/' OR COMMAND CONTAINS '/*')) " +
                        "OR (COMMAND CONTAINS 'CHMOD' AND COMMAND CONTAINS '777' AND COMMAND CONTAINS '/') " +
                        "OR (COMMAND CONTAINS 'DD' AND COMMAND CONTAINS 'IF=' AND (COMMAND CONTAINS '/DEV/ZERO' OR COMMAND CONTAINS '/DEV/RANDOM')) " +
                        "OR (COMMAND CONTAINS 'MKFS') " +
                        "OR (COMMAND CONTAINS 'FORK' AND COMMAND CONTAINS 'BOMB') " +
                        "OR (COMMAND CONTAINS 'SUDO' AND (COMMAND CONTAINS 'RM' OR COMMAND CONTAINS 'MKFS' OR COMMAND CONTAINS 'DD' OR COMMAND CONTAINS 'SHUTDOWN' OR COMMAND CONTAINS 'REBOOT'))")
                .enabled(false)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }
}