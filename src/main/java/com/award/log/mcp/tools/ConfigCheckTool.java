package com.award.log.mcp.tools;

import com.award.log.mcp.AbstractCommandExecutor;
import com.award.log.mcp.McpToolResponses;
import com.award.log.mcp.MinPrivilegeExecutor;
import com.award.log.security.OpsPathPolicy;
import com.award.log.util.OsRuntime;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
@Component
public class ConfigCheckTool extends AbstractCommandExecutor {

    private final ObjectMapper objectMapper;
    private final MinPrivilegeExecutor minPrivilegeExecutor;
    private final OpsPathPolicy opsPathPolicy;

    @Autowired
    public ConfigCheckTool(
            @Autowired(required = false) ExecutorService mcpToolExecutor,
            ObjectMapper objectMapper,
            MinPrivilegeExecutor minPrivilegeExecutor,
            OpsPathPolicy opsPathPolicy) {
        super(mcpToolExecutor != null ? mcpToolExecutor : Executors.newCachedThreadPool());
        this.objectMapper = objectMapper;
        this.minPrivilegeExecutor = minPrivilegeExecutor;
        this.opsPathPolicy = opsPathPolicy;
    }

    @Tool(name = "checkConfig", description = "检查配置文件语法是否正确，支持 nginx、sshd、crontab 等常见配置文件类型，自动识别类型并执行相应验证命令")
    public String checkConfig(
            @ToolParam(description = "配置文件的完整路径（必填）", required = true) String configPath
    ) throws JsonProcessingException {
        long startTime = System.currentTimeMillis();

        if (configPath == null || configPath.isBlank()) {
            return McpToolResponses.error(objectMapper, "配置文件路径不能为空", startTime);
        }

        String normalizedPath = configPath.trim();
        log.info("开始检查配置文件语法，路径: {}", normalizedPath);

        try {
            if (!opsPathPolicy.isAllowedConfigCheckPath(normalizedPath)) {
                return McpToolResponses.error(objectMapper, opsPathPolicy.rejectReason("配置文件路径"), startTime);
            }

            String lowerPath = normalizedPath.toLowerCase();
            String command;
            String configType;

            if (lowerPath.contains("nginx")) {
                command = "nginx -t -c " + normalizedPath;
                configType = "nginx";
            } else if (lowerPath.contains("sshd") || lowerPath.contains("ssh")) {
                command = "sshd -t -f " + normalizedPath;
                configType = "sshd";
            } else if (lowerPath.contains("crontab")) {
                command = "crontab -l";
                configType = "crontab";
            } else if (lowerPath.endsWith(".sh")) {
                command = "sh -n " + normalizedPath;
                configType = "shell";
            } else {
                command = "sh -n " + normalizedPath;
                configType = "generic";
            }

            AbstractCommandExecutor.CommandResult result;
            if (OsRuntime.isWindows()) {
                String safe = normalizedPath.replace("'", "''");
                String ps;
                if (lowerPath.endsWith(".xml") || lowerPath.endsWith(".config")) {
                    ps = "try { [void]([xml](Get-Content -LiteralPath '" + safe + "' -Raw)); 'XML_SYNTAX_OK' } catch { $_.Exception.Message }";
                } else if (lowerPath.endsWith(".ps1")) {
                    ps = "$errs=$null;[void][System.Management.Automation.Language.Parser]::ParseFile('" + safe + "',[ref]$null,[ref]$errs); if ($errs) { $errs } else { 'PS1_SYNTAX_OK' }";
                } else if (lowerPath.endsWith(".json")) {
                    ps = "try { Get-Content -LiteralPath '" + safe + "' -Raw | ConvertFrom-Json | Out-Null; 'JSON_OK' } catch { $_.Exception.Message }";
                } else {
                    ps = "if (Test-Path -LiteralPath '" + safe + "') { 'READ_OK' } else { 'MISSING' }";
                }
                result = minPrivilegeExecutor.executeSafely(
                        List.of("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", ps), 60_000L);
            } else {
                result = minPrivilegeExecutor.executeSafely(
                        List.of("sh", "-c", command + " 2>&1")
                );
            }

            String output = result.output() != null ? result.output() : "";
            String error = result.error() != null ? result.error() : "";
            String combinedOutput = (output + " " + error).trim();

            boolean passed = result.success() &&
                (combinedOutput.isEmpty() ||
                 combinedOutput.toLowerCase().contains("ok") ||
                 combinedOutput.toLowerCase().contains("syntax ok") ||
                 combinedOutput.toLowerCase().contains("successful"));

            String data = String.format(
                "{\"passed\":%s,\"configType\":\"%s\",\"configPath\":\"%s\",\"exitCode\":%d,\"message\":\"%s\"}",
                passed, configType, escapeJson(normalizedPath), result.exitCode(), escapeJson(combinedOutput)
            );

            long duration = System.currentTimeMillis() - startTime;
            log.info("配置文件检查完成，类型: {}, 结果: {}, 退出码: {}, 耗时: {}ms",
                configType, passed ? "通过" : "失败", result.exitCode(), duration);

            return passed
                    ? McpToolResponses.success(objectMapper, data, duration)
                    : McpToolResponses.warn(objectMapper, data, duration);

        } catch (Exception e) {
            log.error("检查配置文件时发生异常", e);
            return McpToolResponses.error(objectMapper, "检查配置文件时发生异常: " + e.getMessage(), startTime);
        }
    }

    private String escapeJson(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\")
                     .replace("\"", "\\\"")
                     .replace("\n", "\\n")
                     .replace("\r", "\\r")
                     .replace("\t", "\\t");
    }
}
