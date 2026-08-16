package com.award.log.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * READ_ONLY 工具面下禁止的 MCP 工具名（与 Bean 短类名一致），支持配置追加。
 */
@Component
public class ReadOnlySurfaceDenylist {

    private static final Set<String> BASE = Set.of(
            "CleanTempTool",
            "LogCleanupTool",
            "ServiceRestartTool",
            "PrivilegeTool",
            "DiskOpsTool",
            "LogOpsTool",
            "ServiceOpsTool",
            "ContainerOpsTool",
            "ProcessOpsTool"
    );

    private final Set<String> denied;

    public ReadOnlySurfaceDenylist(
            @Value("${agent.security.read-only-extra-denied-tool-beans:}") String extraBeansCsv) {
        Set<String> merged = new HashSet<>(BASE);
        merged.addAll(parseCsv(extraBeansCsv));
        this.denied = Set.copyOf(merged);
    }

    private static Set<String> parseCsv(String raw) {
        if (raw == null || raw.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(raw.split(","))
                .map(s -> s.trim())
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
    }

    public boolean denies(String toolOrBeanSimpleName) {
        return toolOrBeanSimpleName != null && denied.contains(toolOrBeanSimpleName.trim());
    }

    public Set<String> snapshot() {
        return denied;
    }
}
