package com.award.log.service;

import com.award.log.config.AgentOpsProperties;
import com.award.log.config.SystemConfigFileSupport;
import com.award.log.security.OpsPathPolicy;
import com.award.log.util.OsRuntime;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 路径/服务白名单：默认来自 {@link AgentOpsProperties}，可通过前端保存覆盖到 {@code agent-path-policy-overrides.json} 并热加载。
 */
@Slf4j
@Service
@org.springframework.context.annotation.DependsOn("opsPathPolicy")
public class AgentPathPolicyService {

    private static final int MAX_LIST_SIZE = 48;

    private final AgentOpsProperties agentOpsProperties;
    private final OpsPathPolicy opsPathPolicy;
    private final ObjectMapper objectMapper;
    private final Path overrideFilePath;

    public AgentPathPolicyService(
            AgentOpsProperties agentOpsProperties,
            OpsPathPolicy opsPathPolicy,
            ObjectMapper objectMapper) {
        this.agentOpsProperties = agentOpsProperties;
        this.opsPathPolicy = opsPathPolicy;
        this.objectMapper = objectMapper;
        this.overrideFilePath = SystemConfigFileSupport.pathPolicyFile();
    }

    @PostConstruct
    void loadOverridesOnStartup() {
        applyOverridesFromFileIfPresent();
    }

    public Map<String, Object> getEffectivePolicyView() {
        synchronized (this) {
            // 返回内存中已生效策略（save/启动加载后由 opsPathPolicy.applyFrom 更新），避免每次 GET 重读文件覆盖未落盘状态
            Map<String, Object> view = new LinkedHashMap<>();
            boolean win = OsRuntime.isWindows();
            view.put("platform", win ? "windows" : "linux");
            view.put("policyVersion", opsPathPolicy.getPolicyVersion());
            view.put("overrideFile", overrideFilePath.toAbsolutePath().toString());
            view.put("overrideFileExists", overrideFilePath.toFile().exists());
            view.put("readPrefixes", opsPathPolicy.snapshotReadPrefixes());
            view.put("cleanRoots", opsPathPolicy.snapshotTempCleanRoots());
            view.put("logCleanupRoots", opsPathPolicy.snapshotLogCleanupRoots());
            view.put("serviceRestartAllowlist", copyList(agentOpsProperties.getServiceRestart().getAllowlist()));
            view.put("deniedSubstrings", copyList(agentOpsProperties.getPaths().getDeniedSubstrings()));
            view.put("note", "禁止路径(deniedSubstrings)仅能在 application.yml 调整；此处保存后立即生效，无需重启。");
            return view;
        }
    }

    public Map<String, Object> saveEditablePolicy(Map<String, Object> body) {
        synchronized (this) {
            List<String> read = normalizePathList(asStringList(body.get("readPrefixes")), OsRuntime.isWindows());
            List<String> clean = normalizePathList(asStringList(body.get("cleanRoots")), OsRuntime.isWindows());
            List<String> logCleanup = normalizePathList(asStringList(body.get("logCleanupRoots")), OsRuntime.isWindows());
            List<String> services = normalizeServiceList(asStringList(body.get("serviceRestartAllowlist")));

            validateNoDenied(read, "可读路径");
            validateNoDenied(clean, "临时清理路径");
            validateNoDenied(logCleanup, "日志清理路径");

            boolean win = OsRuntime.isWindows();
            if (win) {
                agentOpsProperties.getPaths().setWindowsReadPrefixes(read);
                agentOpsProperties.getPaths().setWindowsCleanRoots(clean);
                agentOpsProperties.getPaths().setWindowsLogCleanupRoots(logCleanup);
            } else {
                agentOpsProperties.getPaths().setReadPrefixes(read);
                agentOpsProperties.getPaths().setCleanRoots(clean);
                agentOpsProperties.getPaths().setLogCleanupRoots(logCleanup);
            }
            agentOpsProperties.getServiceRestart().setAllowlist(services);

            opsPathPolicy.applyFrom(agentOpsProperties.getPaths());

            Map<String, Object> persisted = new LinkedHashMap<>();
            persisted.put("readPrefixes", read);
            persisted.put("cleanRoots", clean);
            persisted.put("logCleanupRoots", logCleanup);
            persisted.put("serviceRestartAllowlist", services);
            persisted.put("savedAt", java.time.LocalDateTime.now().toString());
            persisted.put("platform", win ? "windows" : "linux");
            writeOverrideFile(persisted);

            log.info("[AgentPathPolicy] 已保存前端白名单覆盖: read={} clean={} logCleanup={} services={}",
                    read.size(), clean.size(), logCleanup.size(), services.size());
            Map<String, Object> result = getEffectivePolicyView();
            result.put("saved", true);
            return result;
        }
    }

    public void applyOverridesFromFileIfPresent() {
        File file = overrideFilePath.toFile();
        if (!file.exists()) {
            return;
        }
        try {
            Map<String, Object> root = objectMapper.readValue(file, new TypeReference<Map<String, Object>>() {});
            boolean win = OsRuntime.isWindows();
            List<String> read = normalizePathList(asStringList(root.get("readPrefixes")), win);
            List<String> clean = normalizePathList(asStringList(root.get("cleanRoots")), win);
            List<String> logCleanup = normalizePathList(asStringList(root.get("logCleanupRoots")), win);
            List<String> services = normalizeServiceList(asStringList(root.get("serviceRestartAllowlist")));

            if (!read.isEmpty() || !clean.isEmpty() || !logCleanup.isEmpty()) {
                if (win) {
                    if (!read.isEmpty()) {
                        agentOpsProperties.getPaths().setWindowsReadPrefixes(read);
                    }
                    if (!clean.isEmpty()) {
                        agentOpsProperties.getPaths().setWindowsCleanRoots(clean);
                    }
                    if (!logCleanup.isEmpty()) {
                        agentOpsProperties.getPaths().setWindowsLogCleanupRoots(logCleanup);
                    }
                } else {
                    if (!read.isEmpty()) {
                        agentOpsProperties.getPaths().setReadPrefixes(read);
                    }
                    if (!clean.isEmpty()) {
                        agentOpsProperties.getPaths().setCleanRoots(clean);
                    }
                    if (!logCleanup.isEmpty()) {
                        agentOpsProperties.getPaths().setLogCleanupRoots(logCleanup);
                    }
                }
                opsPathPolicy.applyFrom(agentOpsProperties.getPaths());
            }
            if (!services.isEmpty()) {
                agentOpsProperties.getServiceRestart().setAllowlist(services);
            }
        } catch (Exception e) {
            log.warn("[AgentPathPolicy] 读取覆盖文件失败: {}", e.getMessage());
        }
    }

    private void writeOverrideFile(Map<String, Object> body) {
        try {
            SystemConfigFileSupport.writeJsonFile(overrideFilePath, body);
        } catch (Exception e) {
            throw new IllegalStateException("写入白名单覆盖文件失败: " + e.getMessage(), e);
        }
    }

    private static List<String> normalizePathList(List<String> raw, boolean windows) {
        if (raw == null || raw.isEmpty()) {
            throw new IllegalArgumentException("路径列表不能为空");
        }
        if (raw.size() > MAX_LIST_SIZE) {
            throw new IllegalArgumentException("路径列表最多 " + MAX_LIST_SIZE + " 条");
        }
        List<String> out = new ArrayList<>();
        for (String p : raw) {
            if (p == null || p.isBlank()) {
                continue;
            }
            String t = p.trim();
            if (windows) {
                if (!t.matches("(?i)[A-Za-z]:[/\\\\].*") && !t.startsWith("\\\\")) {
                    throw new IllegalArgumentException("Windows 路径须为盘符路径，如 C:/logs: " + t);
                }
            } else {
                if (!t.startsWith("/")) {
                    throw new IllegalArgumentException("Linux 路径须以 / 开头: " + t);
                }
            }
            if (!out.contains(t)) {
                out.add(t);
            }
        }
        if (out.isEmpty()) {
            throw new IllegalArgumentException("路径列表不能为空");
        }
        return out;
    }

    private static List<String> normalizeServiceList(List<String> raw) {
        if (raw == null) {
            return List.of();
        }
        if (raw.size() > MAX_LIST_SIZE) {
            throw new IllegalArgumentException("服务白名单最多 " + MAX_LIST_SIZE + " 条");
        }
        return raw.stream()
                .filter(s -> s != null && !s.isBlank())
                .map(s -> s.trim().toLowerCase(Locale.ROOT).replace(".service", ""))
                .distinct()
                .collect(Collectors.toList());
    }

    private void validateNoDenied(List<String> paths, String category) {
        List<String> denied = agentOpsProperties.getPaths().getDeniedSubstrings();
        if (denied == null) {
            return;
        }
        for (String path : paths) {
            String lower = path.toLowerCase(Locale.ROOT);
            for (String d : denied) {
                if (d != null && !d.isBlank() && lower.contains(d.toLowerCase(Locale.ROOT))) {
                    throw new IllegalArgumentException(category + " 含禁止片段 " + d + ": " + path);
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> asStringList(Object value) {
        if (value == null) {
            return List.of();
        }
        if (value instanceof List<?> list) {
            List<String> out = new ArrayList<>();
            for (Object o : list) {
                if (o != null) {
                    out.add(String.valueOf(o));
                }
            }
            return out;
        }
        return List.of(String.valueOf(value));
    }

    private static List<String> copyList(List<String> in) {
        return in == null ? List.of() : List.copyOf(in);
    }
}
