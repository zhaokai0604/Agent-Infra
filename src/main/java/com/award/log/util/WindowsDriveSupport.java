package com.award.log.util;

import com.award.log.security.OpsPathPolicy;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Windows 逻辑盘符发现与各盘常见临时/垃圾目录候选。
 */
public final class WindowsDriveSupport {

    private WindowsDriveSupport() {
    }

    /** 如 {@code C:}、{@code D:}（大写，无尾部分隔符）。 */
    public static List<String> listLogicalDrives() {
        if (!OsRuntime.isWindows()) {
            return List.of();
        }
        List<String> drives = new ArrayList<>();
        File[] roots = File.listRoots();
        if (roots == null) {
            return drives;
        }
        for (File root : roots) {
            String abs = root.getAbsolutePath();
            if (abs == null || abs.length() < 2 || !Character.isLetter(abs.charAt(0))) {
                continue;
            }
            drives.add(Character.toUpperCase(abs.charAt(0)) + ":");
        }
        return drives;
    }

    /**
     * 各盘符下可扫描/清理的 Temp 类目录（去重、仅保留路径存在或父盘可访问的项）。
     */
    public static List<String> discoverTempCleanCandidates() {
        if (!OsRuntime.isWindows()) {
            return List.of();
        }
        Set<String> out = new LinkedHashSet<>();
        for (String drive : listLogicalDrives()) {
            addIfReachable(out, drive + "/Temp");
            addIfReachable(out, drive + "/TEMP");
            if ("C:".equalsIgnoreCase(drive)) {
                addIfReachable(out, drive + "/Windows/Temp");
            }
        }
        String userTemp = System.getenv("TEMP");
        if (userTemp != null && !userTemp.isBlank()) {
            out.add(OpsPathPolicy.normalizeWindowsPath(userTemp));
        }
        String tmp = System.getenv("TMP");
        if (tmp != null && !tmp.isBlank()) {
            out.add(OpsPathPolicy.normalizeWindowsPath(tmp));
        }
        return List.copyOf(out);
    }

    /** 各盘根目录（用于热点扫描，须在 read 白名单内）。 */
    public static List<String> discoverDriveRootsForInsight() {
        List<String> roots = new ArrayList<>();
        for (String drive : listLogicalDrives()) {
            roots.add(drive + "/");
        }
        return roots;
    }

    private static void addIfReachable(Set<String> out, String path) {
        String norm = OpsPathPolicy.normalizeWindowsPath(path);
        if (norm.isEmpty()) {
            return;
        }
        try {
            Path p = Path.of(norm.replace('/', File.separatorChar));
            if (Files.isDirectory(p)) {
                out.add(norm);
            }
        } catch (Exception ignored) {
            out.add(norm);
        }
    }

    public static String driveLabel(String normalizedPath) {
        if (normalizedPath == null || normalizedPath.isBlank()) {
            return "?";
        }
        String n = OpsPathPolicy.normalizeWindowsPath(normalizedPath);
        int colon = n.indexOf(':');
        if (colon > 0) {
            return n.substring(0, colon + 1).toUpperCase(Locale.ROOT);
        }
        return n;
    }

    /**
     * 系统级 Temp（如 C:/Windows/Temp）通常需 Administrator，自动批量清理时跳过，避免「假成功」。
     */
    public static boolean isSystemElevatedTempRoot(String path) {
        if (!OsRuntime.isWindows() || path == null || path.isBlank()) {
            return false;
        }
        String n = OpsPathPolicy.normalizeWindowsPath(path);
        return "C:/Windows/Temp".equalsIgnoreCase(n);
    }
}
