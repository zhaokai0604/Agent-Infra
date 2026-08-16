package com.award.log.util;

import java.util.LinkedHashMap;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;

/**
 * 运行环境探测（Agent / MCP 在 Windows 与 Linux/麒麟 下分支用）。
 * <p>赛题交付目标：银河麒麟高级服务器版 V11 + LoongArch64。</p>
 */
public final class OsRuntime {

    private static final String OS_NAME = System.getProperty("os.name", "");
    private static final String OS_VERSION = System.getProperty("os.version", "");
    private static final String OS_ARCH = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
    private static final String OS_NAME_LOWER = OS_NAME.toLowerCase(Locale.ROOT);

    private static final boolean WINDOWS = OS_NAME_LOWER.contains("win");
    private static final Map<String, String> OS_RELEASE = loadOsRelease();
    private static final boolean KYLIN = OS_NAME_LOWER.contains("kylin")
            || OsReleaseParser.isKylin(OS_RELEASE)
            || readFileContains("/etc/.kyinfo", "kylin")
            || readFileContains("/etc/.kyinfo", "麒麟");
    private static final boolean LOONGARCH = OS_ARCH.contains("loongarch")
            || OS_ARCH.equals("mips64")
            || OS_ARCH.contains("loongson");

    private OsRuntime() {
    }

    public static boolean isWindows() {
        return WINDOWS;
    }

    public static boolean isUnixLike() {
        return !WINDOWS;
    }

    /** 银河麒麟（含高级服务器版 V11 等） */
    public static boolean isKylin() {
        return !WINDOWS && KYLIN;
    }

    /** 龙芯 LoongArch64（赛题龙架构） */
    public static boolean isLoongArch() {
        return LOONGARCH;
    }

    public static String osName() {
        return firstNonBlank(OS_RELEASE.get("PRETTY_NAME"), OS_RELEASE.get("NAME"), OS_NAME);
    }

    public static String osVersion() {
        return firstNonBlank(OS_RELEASE.get("VERSION_ID"), OS_RELEASE.get("VERSION"), OS_VERSION);
    }

    public static String osArch() {
        return System.getProperty("os.arch", "");
    }

    /**
     * 供启动日志、验收 API、审计上下文使用。
     */
    public static Map<String, Object> platformSummary() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("osName", OS_NAME);
        m.put("osVersion", OS_VERSION);
        m.put("distributionId", OS_RELEASE.getOrDefault("ID", ""));
        m.put("distributionName", firstNonBlank(OS_RELEASE.get("PRETTY_NAME"), OS_RELEASE.get("NAME"), ""));
        m.put("distributionVersion", firstNonBlank(OS_RELEASE.get("VERSION_ID"), OS_RELEASE.get("VERSION"), ""));
        m.put("osRelease", Collections.unmodifiableMap(OS_RELEASE));
        m.put("osArch", osArch());
        m.put("windows", WINDOWS);
        m.put("unixLike", isUnixLike());
        m.put("kylin", isKylin());
        m.put("loongArch", isLoongArch());
        m.put("deliveryTarget", "Kylin-V11-LoongArch64");
        m.put("deliveryTargetMatch", isKylin() && isLoongArch());
        m.put("javaVersion", System.getProperty("java.version", ""));
        m.put("javaVendor", System.getProperty("java.vendor", ""));
        return m;
    }

    private static boolean readFileContains(String path, String needle) {
        if (WINDOWS) {
            return false;
        }
        try {
            String content = java.nio.file.Files.readString(java.nio.file.Path.of(path))
                    .toLowerCase(Locale.ROOT);
            return content.contains(needle.toLowerCase(Locale.ROOT));
        } catch (Exception e) {
            return false;
        }
    }

    private static Map<String, String> loadOsRelease() {
        if (WINDOWS) {
            return Map.of();
        }
        Map<String, String> merged = new LinkedHashMap<>();
        for (String path : new String[]{"/etc/os-release", "/usr/lib/os-release", "/etc/lsb-release", "/etc/.kyinfo"}) {
            try {
                if (Files.isRegularFile(Path.of(path))) {
                    for (Map.Entry<String, String> entry : OsReleaseParser.parse(Files.readString(Path.of(path))).entrySet()) {
                        merged.putIfAbsent(entry.getKey(), entry.getValue());
                    }
                }
            } catch (Exception ignored) {
                // 发行版标识文件不可读时继续尝试下一个标准位置。
            }
        }
        return Map.copyOf(merged);
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }
}
