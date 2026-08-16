package com.award.log.mcp;

import com.award.log.config.AgentOpsProperties;
import com.award.log.security.OpsPathPolicy;
import com.award.log.util.OsRuntime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 删除日志/临时文件前的保护：避免误删数据库与中间件关键文件。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LogSafetyClassifier {

    private final OpsPathPolicy opsPathPolicy;
    private final AgentOpsProperties agentOpsProperties;

    private static final Pattern PROTECTED_PATH = Pattern.compile(
            "(/var/lib/mysql|/var/lib/mariadb|/var/lib/pgsql|/var/lib/postgresql|"
                    + "/var/lib/redis|/var/lib/mongodb|/var/lib/docker|"
                    + "mysql|mariadb|postgresql|mongodb|redis|ib_logfile|ibdata|"
                    + "slow\\.log|binlog|relay-log|audit\\.log)",
            Pattern.CASE_INSENSITIVE);

    public record Verdict(boolean allowed, String reason) {
        public static Verdict allow() {
            return new Verdict(true, null);
        }

        public static Verdict deny(String reason) {
            return new Verdict(false, reason);
        }
    }

    public record FilterResult(List<String> allowed, List<String> deniedReasons) {}

    public Verdict classifyPath(String filePath) {
        if (filePath == null || filePath.isBlank()) {
            return Verdict.deny("空路径");
        }
        String norm = filePath.replace('\\', '/').toLowerCase(Locale.ROOT);
        if (norm.contains("..")) {
            return Verdict.deny("路径穿越");
        }
        for (String frag : opsPathPolicy.getLogProtectedSubstrings()) {
            if (!frag.isBlank() && norm.contains(frag.trim().toLowerCase(Locale.ROOT))) {
                return Verdict.deny("命中保护路径规则: " + frag);
            }
        }
        if (PROTECTED_PATH.matcher(norm).find()) {
            return Verdict.deny("疑似数据库/中间件关键文件");
        }
        return Verdict.allow();
    }

    /**
     * 批量过滤待删文件；可选对「允许」列表再做 lsof 占用检查（仅 Linux）。
     */
    public FilterResult filterDeletable(List<String> candidates) {
        List<String> allowed = new ArrayList<>();
        List<String> denied = new ArrayList<>();
        if (candidates == null) {
            return new FilterResult(allowed, denied);
        }
        for (String path : candidates) {
            Verdict v = classifyPath(path);
            if (!v.allowed()) {
                denied.add(path + " → " + v.reason());
                continue;
            }
            if (agentOpsProperties.getLogSafety().isLsofCheckEnabled()
                    && !OsRuntime.isWindows()
                    && isFileHeldByDataService(path)) {
                denied.add(path + " → 文件正被数据库/守护进程占用 (lsof)");
                continue;
            }
            allowed.add(path);
        }
        return new FilterResult(allowed, denied);
    }

    private boolean isFileHeldByDataService(String path) {
        try {
            Process p = new ProcessBuilder("lsof", "-nP", path)
                    .redirectErrorStream(true)
                    .start();
            String out = new String(p.getInputStream().readAllBytes());
            p.waitFor();
            if (out.isBlank()) {
                return false;
            }
            String lower = out.toLowerCase(Locale.ROOT);
            return lower.contains("mysql") || lower.contains("mariadb") || lower.contains("postgres")
                    || lower.contains("mongod") || lower.contains("redis-server");
        } catch (Exception e) {
            log.debug("lsof 跳过 {}: {}", path, e.getMessage());
            return false;
        }
    }

}
