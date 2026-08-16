package com.award.log.collector;

import com.award.log.util.OsRuntime;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 日志文件采集根路径：支持 {@code auto} / {@code *} 按操作系统自动感知常见日志目录与磁盘根。
 */
public final class LogCollectorPathResolver {

    public static final String AUTO = "auto";

    private LogCollectorPathResolver() {
    }

    public static boolean isAutoMode(String configured) {
        if (configured == null || configured.isBlank()) {
            return true;
        }
        String v = configured.trim();
        return AUTO.equalsIgnoreCase(v) || "*".equals(v) || "ALL".equalsIgnoreCase(v);
    }

    /**
     * @param configured {@code log.collector.file.path}；空/auto/* 时按 OS 自动感知<strong>常见日志目录</strong>（不扫描整盘）
     */
    public static List<String> resolve(String configured) {
        if (!isAutoMode(configured)) {
            return List.of(configured.trim());
        }
        Set<String> roots = new LinkedHashSet<>();
        if (OsRuntime.isWindows()) {
            addEnvChildPath(roots, System.getenv("TEMP"), "logs");
            addEnvChildPath(roots, System.getenv("LOCALAPPDATA"), "Temp");
            addEnvChildPath(roots, System.getenv("ProgramData"), "logs");
            addEnvChildPath(roots, System.getenv("ProgramData"), "Microsoft", "Windows", "WER", "ReportArchive");
            addEnvChildPath(roots, System.getenv("ProgramData"), "Microsoft", "Windows", "WER", "ReportQueue");
            addIfExists(roots, "C:\\Windows\\Logs");
            addIfExists(roots, "C:\\inetpub\\logs");
            addIfExists(roots, System.getProperty("user.dir") + "\\logs");
        } else {
            addIfExists(roots, "/var/log");
            addIfExists(roots, "/var/adm");
            addIfExists(roots, "/opt");
            addIfExists(roots, "/usr/local/var/log");
            addIfExists(roots, "/tmp");
            addIfExists(roots, "/data/log");
            addIfExists(roots, "/data/logs");
        }
        addIfExists(roots, "logs");
        addIfExists(roots, "./logs");
        return new ArrayList<>(roots);
    }

    public static String displaySummary(String configured, List<String> resolved) {
        if (isAutoMode(configured)) {
            return "自动感知（" + resolved.size() + " 个根路径）";
        }
        return resolved.isEmpty() ? "未配置" : String.join("; ", resolved);
    }

    private static void addEnvPath(Set<String> roots, String path) {
        if (path != null && !path.isBlank()) {
            addIfExists(roots, path.trim());
        }
    }

    private static void addEnvChildPath(Set<String> roots, String base, String first, String... more) {
        if (base == null || base.isBlank()) {
            return;
        }
        try {
            Path child = Path.of(base.trim()).resolve(first);
            if (more != null) {
                for (String part : more) {
                    child = child.resolve(part);
                }
            }
            addIfExists(roots, child.toString());
        } catch (Exception ignored) {
            // 非法路径跳过
        }
    }

    private static void addIfExists(Set<String> roots, String path) {
        if (path == null || path.isBlank()) {
            return;
        }
        try {
            Path p = Path.of(path.trim());
            if (Files.exists(p)) {
                roots.add(normalizeRoot(p.toAbsolutePath().toString()));
            }
        } catch (Exception ignored) {
            // 非法路径跳过
        }
    }

    private static String normalizeRoot(String path) {
        if (path == null) {
            return "";
        }
        String p = path.trim();
        if (OsRuntime.isWindows() && p.length() == 2 && p.charAt(1) == ':') {
            return p.toUpperCase(Locale.ROOT) + "\\";
        }
        if (p.endsWith("/") && p.length() > 1) {
            p = p.substring(0, p.length() - 1);
        }
        return p;
    }
}
