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

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
@Component
public class PrivilegeTool extends AbstractCommandExecutor {

    private final ObjectMapper objectMapper;
    private final MinPrivilegeExecutor minPrivilegeExecutor;
    private final OpsPathPolicy opsPathPolicy;

    @Autowired
    public PrivilegeTool(
            @Autowired(required = false) ExecutorService mcpToolExecutor,
            ObjectMapper objectMapper,
            MinPrivilegeExecutor minPrivilegeExecutor,
            OpsPathPolicy opsPathPolicy) {
        super(mcpToolExecutor != null ? mcpToolExecutor : Executors.newCachedThreadPool());
        this.objectMapper = objectMapper;
        this.minPrivilegeExecutor = minPrivilegeExecutor;
        this.opsPathPolicy = opsPathPolicy;
    }

    @Tool(name = "checkPrivilege", description = "检查当前用户对文件或命令的权限，支持读、写、执行权限检查，以及 sudo 权限验证")
    public String checkPrivilege(
            @ToolParam(description = "文件路径或命令名称（必填）", required = true) String resource,
            @ToolParam(description = "操作类型：read、write、execute（默认 execute）", required = false) String action
    ) throws JsonProcessingException {
        long startTime = System.currentTimeMillis();

        if (resource == null || resource.isBlank()) {
            return McpToolResponses.error(objectMapper, "资源路径不能为空", startTime);
        }

        String targetResource = resource.trim();
        String checkAction = (action != null && !action.isBlank()) ? action.toLowerCase() : "execute";

        if (!checkAction.matches("^(read|write|execute|r|w|x)$")) {
            return McpToolResponses.error(objectMapper, "无效的操作类型，请使用 read、write 或 execute", startTime);
        }

        log.info("开始检查权限，资源: {}, 操作: {}", targetResource, checkAction);

        try {
            String currentUser = getCurrentUser();
            boolean isFile = !isCommand(targetResource);
            boolean hasPermission;
            boolean needsSudo = false;
            String resolvedPath = targetResource;

            if (isFile) {
                if (!opsPathPolicy.isAllowedPrivilegeProbePath(targetResource)) {
                    return McpToolResponses.error(objectMapper, opsPathPolicy.rejectReason("权限探测路径"), startTime);
                }
                Path filePath = Paths.get(targetResource);
                if (!Files.exists(filePath)) {
                    String data = String.format(
                            "{\"hasPrivilege\":false,\"resource\":\"%s\",\"resolvedPath\":\"%s\",\"action\":\"%s\"," +
                            "\"currentUser\":\"%s\",\"isFile\":true,\"targetUnreachable\":true," +
                            "\"suggestion\":\"目标路径不存在或不可达\"}",
                            targetResource, targetResource, checkAction, currentUser);
                    long duration = System.currentTimeMillis() - startTime;
                    return McpToolResponses.warn(objectMapper, data, duration);
                }
                hasPermission = checkFilePermission(targetResource, checkAction);
            } else {
                resolvedPath = findCommandPath(targetResource);
                if (resolvedPath == null) {
                    String data = String.format(
                        "{\"resource\":\"%s\",\"action\":\"%s\",\"currentUser\":\"%s\"," +
                        "\"hasPrivilege\":false,\"error\":\"命令不存在\",\"suggestion\":\"请检查命令是否已安装\"}",
                        targetResource, checkAction, currentUser
                    );
                    long duration = System.currentTimeMillis() - startTime;
                    return McpToolResponses.success(objectMapper, data, duration);
                }
                if (!opsPathPolicy.isAllowedPrivilegeProbePath(resolvedPath)) {
                    return McpToolResponses.error(objectMapper, opsPathPolicy.rejectReason("权限探测路径"), startTime);
                }
                hasPermission = checkFilePermission(resolvedPath, checkAction);
            }

            if (!hasPermission) {
                needsSudo = checkSudoPermission(targetResource);
            }

            String data;
            if (!hasPermission && needsSudo) {
                data = String.format(
                    "{\"hasPrivilege\":false,\"resource\":\"%s\",\"resolvedPath\":\"%s\",\"action\":\"%s\"," +
                    "\"currentUser\":\"%s\",\"isFile\":%s,\"needsSudo\":true," +
                    "\"suggestion\":\"建议使用 sudo 执行: sudo %s\"}",
                    targetResource, resolvedPath, checkAction, currentUser, isFile, targetResource
                );
            } else if (!hasPermission) {
                data = String.format(
                    "{\"hasPrivilege\":false,\"resource\":\"%s\",\"resolvedPath\":\"%s\",\"action\":\"%s\"," +
                    "\"currentUser\":\"%s\",\"isFile\":%s,\"needsSudo\":false," +
                    "\"suggestion\":\"当前用户无权限，请联系管理员\"}",
                    targetResource, resolvedPath, checkAction, currentUser, isFile
                );
            } else {
                data = String.format(
                    "{\"hasPrivilege\":true,\"resource\":\"%s\",\"resolvedPath\":\"%s\",\"action\":\"%s\"," +
                    "\"currentUser\":\"%s\",\"isFile\":%s,\"needsSudo\":false," +
                    "\"suggestion\":\"权限检查通过\"}",
                    targetResource, resolvedPath, checkAction, currentUser, isFile
                );
            }

            long duration = System.currentTimeMillis() - startTime;
            log.info("权限检查完成，资源: {}, 操作: {}, 有权限: {}, 需要sudo: {}, 耗时: {}ms",
                targetResource, checkAction, hasPermission, needsSudo, duration);

            return McpToolResponses.success(objectMapper, data, duration);

        } catch (Exception e) {
            log.error("检查权限时发生异常", e);
            return McpToolResponses.error(objectMapper, "检查权限时发生异常: " + e.getMessage(), startTime);
        }
    }

    /** 裸命令名 vs 文件路径（支持 Unix 绝对路径与 Windows 盘符/UNC）。 */
    private boolean isCommand(String path) {
        if (path == null || path.isBlank()) {
            return true;
        }
        String t = path.trim();
        if (t.startsWith("/")) {
            return false;
        }
        if (t.length() >= 2 && Character.isLetter(t.charAt(0)) && t.charAt(1) == ':') {
            return false;
        }
        if (t.startsWith("\\\\")) {
            return false;
        }
        return true;
    }

    private String getCurrentUser() {
        try {
            AbstractCommandExecutor.CommandResult result = minPrivilegeExecutor.executeSafely(List.of("whoami"));
            return result.output() != null ? result.output().trim() : "unknown";
        } catch (Exception e) {
            return "unknown";
        }
    }

    private String findCommandPath(String command) {
        try {
            List<String> lookup = OsRuntime.isWindows()
                    ? List.of("where.exe", command)
                    : List.of("which", command);
            AbstractCommandExecutor.CommandResult result = minPrivilegeExecutor.executeSafely(lookup);
            if (result.success() && result.output() != null) {
                String out = result.output().trim();
                int nl = out.indexOf('\r');
                if (nl < 0) {
                    nl = out.indexOf('\n');
                }
                return nl >= 0 ? out.substring(0, nl).trim() : out;
            }
        } catch (Exception e) {
            log.debug("查找命令路径失败: {}", command);
        }
        return null;
    }

    private boolean checkFilePermission(String path, String action) {
        try {
            Path p = Paths.get(path);
            if (!Files.exists(p)) {
                return false;
            }
            return switch (action) {
                case "read", "r" -> Files.isReadable(p);
                case "write", "w" -> Files.isWritable(p);
                default -> Files.isExecutable(p);
            };
        } catch (Exception e) {
            return false;
        }
    }

    private boolean checkSudoPermission(String command) {
        if (OsRuntime.isWindows()) {
            return false;
        }
        try {
            AbstractCommandExecutor.CommandResult result = minPrivilegeExecutor.executeSafely(List.of("sudo", "-l"));
            if (result.success() && result.output() != null) {
                String output = result.output();
                return output.contains(command) || output.contains("ALL");
            }
        } catch (Exception e) {
            log.debug("检查 sudo 权限失败: {}", command);
        }
        return false;
    }
}
