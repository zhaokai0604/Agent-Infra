package com.award.log.security;

import com.award.log.collector.LogCollectorPathResolver;
import com.award.log.config.AgentOpsProperties;
import com.award.log.util.OsRuntime;
import com.award.log.util.WindowsDriveSupport;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 运维工具路径白名单 / 黑名单（国赛「确定性」：禁止任意路径 find/rm/tail）。
 * 列表来源 {@link AgentOpsProperties}，见 {@code agent.paths.*}。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OpsPathPolicy {

    private final AgentOpsProperties agentOpsProperties;

    @Getter
    private String policyVersion = "path-policy-v1";

    /** 与日志采集根目录对齐，避免「生产盘符」上的日志被策略误拦（见 {@code log.collector.file.path}）。 */
    @Value("${log.collector.file.path:}")
    private String logCollectorFilePath;

    private List<String> readPrefixes = List.of();
    private List<String> cleanRoots = List.of();
    private List<String> configPrefixes = List.of();
    private List<String> deniedSubstrings = List.of();
    private List<String> privilegePrefixes = List.of();

    private List<String> windowsReadPrefixes = List.of();
    private List<String> windowsCleanRoots = List.of();
    private List<String> windowsConfigPrefixes = List.of();
    private List<String> windowsPrivilegePrefixes = List.of();

    private List<String> logCleanupRoots = List.of();
    private List<String> windowsLogCleanupRoots = List.of();

    @PostConstruct
    void init() {
        applyFrom(agentOpsProperties.getPaths());
    }

    /**
     * 从配置对象热加载路径策略（启动时与前端保存覆盖后调用）。
     */
    public synchronized void applyFrom(AgentOpsProperties.Paths cfg) {
        if (cfg == null) {
            return;
        }
        policyVersion = cfg.getPolicyVersion();
        readPrefixes = copyNormalize(cfg.getReadPrefixes());
        cleanRoots = copyNormalize(cfg.getCleanRoots());
        logCleanupRoots = copyNormalize(cfg.getLogCleanupRoots());
        configPrefixes = copyNormalize(cfg.getConfigPrefixes());
        deniedSubstrings = copyNormalize(cfg.getDeniedSubstrings());
        privilegePrefixes = copyNormalize(cfg.getPrivilegePrefixes());
        windowsReadPrefixes = copyNormalize(cfg.getWindowsReadPrefixes());
        windowsCleanRoots = copyNormalize(cfg.getWindowsCleanRoots());
        windowsLogCleanupRoots = copyNormalize(cfg.getWindowsLogCleanupRoots());
        windowsConfigPrefixes = copyNormalize(cfg.getWindowsConfigPrefixes());
        windowsPrivilegePrefixes = copyNormalize(cfg.getWindowsPrivilegePrefixes());

        if (!OsRuntime.isWindows()) {
            for (String root : LogCollectorPathResolver.resolve(logCollectorFilePath)) {
                String norm = normalizeUnixPath(root);
                if (!norm.isEmpty()) {
                    List<String> merged = new ArrayList<>(readPrefixes);
                    boolean exists = false;
                    for (String e : merged) {
                        if (normalizeUnixPath(e).equals(norm)) {
                            exists = true;
                            break;
                        }
                    }
                    if (!exists) {
                        merged.add(norm);
                    }
                    readPrefixes = merged;
                }
            }
        }
        if (OsRuntime.isWindows()) {
            String tmp = normalizeWindowsPath(System.getProperty("java.io.tmpdir", ""));
            if (!tmp.isEmpty()) {
                addIfAbsent(windowsReadPrefixes, tmp);
                addIfAbsent(windowsCleanRoots, tmp);
            }
            for (String root : LogCollectorPathResolver.resolve(logCollectorFilePath)) {
                addIfAbsent(windowsReadPrefixes, root.trim());
                if (isWindowsLogCleanupCandidate(root)) {
                    addIfAbsent(windowsLogCleanupRoots, root.trim());
                }
            }
            for (String temp : WindowsDriveSupport.discoverTempCleanCandidates()) {
                addIfAbsent(windowsCleanRoots, temp);
                addIfAbsent(windowsReadPrefixes, temp);
            }
        }
        log.info("OpsPathPolicy [{}] read={} clean={} logCleanup={} config={} privilege={} winRead={} winClean={}",
                policyVersion, readPrefixes, cleanRoots, logCleanupRoots, configPrefixes, privilegePrefixes,
                windowsReadPrefixes, windowsCleanRoots);
    }

    public List<String> snapshotReadPrefixes() {
        return OsRuntime.isWindows() ? List.copyOf(windowsReadPrefixes) : List.copyOf(readPrefixes);
    }

    public synchronized void updateEditablePathLists(boolean windows, List<String> read, List<String> clean, List<String> logCleanup) {
        if (windows) {
            windowsReadPrefixes = copyNormalize(read);
            windowsCleanRoots = copyNormalize(clean);
            windowsLogCleanupRoots = copyNormalize(logCleanup);
        } else {
            readPrefixes = copyNormalize(read);
            cleanRoots = copyNormalize(clean);
            logCleanupRoots = copyNormalize(logCleanup);
        }
    }

    private static void addIfAbsent(List<String> list, String path) {
        String n = normalizeWindowsPath(path);
        if (n.isEmpty()) {
            return;
        }
        for (String e : list) {
            if (normalizeWindowsPath(e).equalsIgnoreCase(n)) {
                return;
            }
        }
        list.add(path.trim());
    }

    private static boolean isWindowsLogCleanupCandidate(String path) {
        String normalized = normalizeWindowsPath(path).toLowerCase();
        if (normalized.isEmpty()) {
            return false;
        }
        return normalized.contains("/logs")
                || normalized.endsWith("/log")
                || normalized.contains("/wer/reportarchive")
                || normalized.contains("/wer/reportqueue");
    }

    private static List<String> copyNormalize(List<String> raw) {
        List<String> out = new ArrayList<>();
        if (raw == null) {
            return out;
        }
        for (String p : raw) {
            if (p != null && !p.isBlank()) {
                out.add(normalizeDirPrefix(p.trim()));
            }
        }
        return out;
    }

    /** 日志删除保护子串（供 {@link com.award.log.mcp.LogSafetyClassifier}）。 */
    public List<String> getLogProtectedSubstrings() {
        return copyNormalize(agentOpsProperties.getPaths().getLogProtectedSubstrings());
    }

    private static String normalizeDirPrefix(String p) {
        String t = p.trim();
        if (t.endsWith("/") && t.length() > 1) {
            t = t.substring(0, t.length() - 1);
        }
        if (t.length() > 2 && t.charAt(1) == ':' && (t.endsWith("\\") || t.endsWith("/"))) {
            t = t.substring(0, t.length() - 1);
        }
        return t;
    }

    /** Windows 盘符路径规范化：统一为 {@code D:/path} 形式（大写盘符）。 */
    public static String normalizeWindowsPath(String path) {
        if (path == null) {
            return "";
        }
        String t = path.trim().replace('\\', '/');
        if (t.isEmpty()) {
            return "";
        }
        if (t.startsWith("//")) {
            return t;
        }
        if (t.length() >= 2 && t.charAt(1) == ':' && Character.isLetter(t.charAt(0))) {
            char d = Character.toUpperCase(t.charAt(0));
            t = d + t.substring(1);
        }
        while (t.length() > 3 && t.endsWith("/")) {
            t = t.substring(0, t.length() - 1);
        }
        return t;
    }

    private static boolean startsWithWindowsPrefix(String normalizedPath, String prefix) {
        String px = normalizeWindowsPath(prefix);
        if (px.isEmpty() || normalizedPath.isEmpty()) {
            return false;
        }
        return normalizedPath.equalsIgnoreCase(px)
                || normalizedPath.toLowerCase().startsWith(px.toLowerCase() + "/");
    }

    /** 规范化为绝对路径样式（Linux）。 */
    public String normalizeUnixPath(String path) {
        if (path == null) {
            return "";
        }
        String t = path.trim().replace('\\', '/');
        if (t.isEmpty()) {
            return "";
        }
        if (!t.startsWith("/")) {
            t = "/" + t;
        }
        while (t.length() > 1 && t.endsWith("/")) {
            t = t.substring(0, t.length() - 1);
        }
        return t;
    }

    public boolean hasTraversalOrNullBytes(String path) {
        if (path == null) {
            return true;
        }
        return path.contains("..") || path.contains("\0");
    }

    public boolean matchesDenied(String path) {
        if (path == null) {
            return true;
        }
        String lower = path.toLowerCase();
        for (String d : deniedSubstrings) {
            if (d != null && !d.isBlank() && lower.contains(d.trim().toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    /** 日志读取类路径（analyzeLogs / tail）。 */
    public boolean isAllowedLogReadPath(String path) {
        if (hasTraversalOrNullBytes(path) || matchesDenied(path)) {
            return false;
        }
        if (OsRuntime.isWindows()) {
            String wn = normalizeWindowsPath(path);
            if (wn.isEmpty() || wn.startsWith("//")) {
                return false;
            }
            for (String prefix : windowsReadPrefixes) {
                if (startsWithWindowsPrefix(wn, prefix)) {
                    return true;
                }
            }
            return false;
        }
        String norm = normalizeUnixPath(path);
        if (norm.isEmpty()) {
            return false;
        }
        for (String prefix : readPrefixes) {
            String px = normalizeUnixPath(prefix);
            if (norm.equals(px) || norm.startsWith(px + "/")) {
                return true;
            }
        }
        return false;
    }

    /** 临时清理 find/rm 的根目录。 */
    public boolean isAllowedCleanDirectory(String path) {
        if (hasTraversalOrNullBytes(path) || matchesDenied(path)) {
            return false;
        }
        if (OsRuntime.isWindows()) {
            String wn = normalizeWindowsPath(path);
            if (wn.isEmpty() || wn.startsWith("//")) {
                return false;
            }
            for (String root : windowsCleanRoots) {
                if (startsWithWindowsPrefix(wn, root)) {
                    return true;
                }
            }
            return false;
        }
        String norm = normalizeUnixPath(path);
        if (norm.isEmpty()) {
            return false;
        }
        for (String root : cleanRoots) {
            String rt = normalizeUnixPath(root);
            if (norm.equals(rt) || norm.startsWith(rt + "/")) {
                return true;
            }
        }
        return false;
    }

    /** LogCleanupTool：仅允许在配置的日志根目录下删除陈旧日志文件。 */
    public boolean isAllowedLogCleanupPath(String path) {
        if (hasTraversalOrNullBytes(path) || matchesDenied(path)) {
            return false;
        }
        if (OsRuntime.isWindows()) {
            String wn = normalizeWindowsPath(path);
            if (wn.isEmpty() || wn.startsWith("//")) {
                return false;
            }
            for (String root : windowsLogCleanupRoots) {
                if (startsWithWindowsPrefix(wn, root)) {
                    return true;
                }
            }
            return false;
        }
        String norm = normalizeUnixPath(path);
        if (norm.isEmpty()) {
            return false;
        }
        for (String root : logCleanupRoots) {
            String rt = normalizeUnixPath(root);
            if (norm.equals(rt) || norm.startsWith(rt + "/")) {
                return true;
            }
        }
        return false;
    }

    /** 配置检查（只读语法校验）允许的路径前缀。 */
    public boolean isAllowedConfigCheckPath(String path) {
        if (hasTraversalOrNullBytes(path) || matchesDenied(path)) {
            return false;
        }
        if (OsRuntime.isWindows()) {
            String wn = normalizeWindowsPath(path);
            if (wn.isEmpty() || wn.startsWith("//")) {
                return false;
            }
            for (String prefix : windowsConfigPrefixes) {
                if (startsWithWindowsPrefix(wn, prefix)) {
                    return true;
                }
            }
            return false;
        }
        String norm = normalizeUnixPath(path);
        if (norm.isEmpty()) {
            return false;
        }
        for (String prefix : configPrefixes) {
            String px = normalizeUnixPath(prefix);
            if (norm.equals(px) || norm.startsWith(px)) {
                return true;
            }
        }
        return false;
    }

    /** 磁盘热点 du 扫描允许的根路径（读日志区 + 临时目录白名单）。 */
    public boolean isAllowedDiskInsightRoot(String path) {
        return isAllowedLogReadPath(path) || isAllowedCleanDirectory(path);
    }

    /** PrivilegeTool：允许探测读权限的路径（日志/临时/配置区 + 系统二进制前缀）。 */
    public boolean isAllowedPrivilegeProbePath(String path) {
        if (hasTraversalOrNullBytes(path) || matchesDenied(path)) {
            return false;
        }
        if (OsRuntime.isWindows()) {
            String wn = normalizeWindowsPath(path);
            if (wn.isEmpty() || wn.startsWith("//")) {
                return false;
            }
            if (isAllowedLogReadPath(path) || isAllowedCleanDirectory(path) || isAllowedConfigCheckPath(path)) {
                return true;
            }
            for (String prefix : windowsPrivilegePrefixes) {
                if (startsWithWindowsPrefix(wn, prefix)) {
                    return true;
                }
            }
            return false;
        }
        String norm = normalizeUnixPath(path);
        if (norm.isEmpty()) {
            return false;
        }
        if (isAllowedLogReadPath(path) || isAllowedCleanDirectory(path) || isAllowedConfigCheckPath(path)) {
            return true;
        }
        for (String prefix : privilegePrefixes) {
            String px = normalizeUnixPath(prefix);
            if (norm.equals(px) || norm.startsWith(px + "/")) {
                return true;
            }
        }
        return false;
    }

    public String rejectReason(String category) {
        return "路径策略拦截[" + policyVersion + "]: " + category + "，请联系管理员调整 agent.paths.* 配置";
    }

    /** 临时目录清理白名单根（当前 OS），供巡检自动修复等内部编排读取。 */
    public List<String> snapshotTempCleanRoots() {
        if (OsRuntime.isWindows()) {
            return List.copyOf(windowsCleanRoots);
        }
        return List.copyOf(cleanRoots);
    }

    /** 日志裁剪白名单根（当前 OS），供巡检自动修复等内部编排读取。 */
    public List<String> snapshotLogCleanupRoots() {
        if (OsRuntime.isWindows()) {
            return List.copyOf(windowsLogCleanupRoots);
        }
        return List.copyOf(logCleanupRoots);
    }
}
