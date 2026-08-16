package com.award.log.util;

import com.award.log.security.OpsPathPolicy;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从自然语言中提取 Windows / Unix 绝对路径（供清理 Playbook 使用）。
 */
public final class OpsPathExtractSupport {

    /** 匹配完整 Windows 路径（字符类内勿写 {@code \\w}，Java 会当成字面量 {@code \} + {@code w}）。 */
    private static final Pattern WINDOWS_PATH = Pattern.compile(
            "([A-Za-z]:(?:\\\\|/)(?:[^\\s\"'<>|?*\\r\\n「」]+))",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern UNIX_PATH = Pattern.compile(
            "(/(?:[\\w\\-.]+/)*[\\w\\-.]+)");

    private OpsPathExtractSupport() {
    }

    public static Optional<String> firstPath(String text) {
        return bestPath(text);
    }

    /** 取文本中最长的一条路径（避免 {@code C:\\Users} 截断完整 Temp 子目录）。 */
    public static Optional<String> bestPath(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        List<String> candidates = collectPathCandidates(text);
        return candidates.stream()
                .filter(s -> s.length() >= 3)
                .max(Comparator.comparingInt(String::length)
                        .thenComparing(Comparator.naturalOrder()));
    }

    public static Optional<String> firstPathFromConversation(String currentMessage, List<String> priorUserMessages) {
        return bestPathFromConversation(currentMessage, priorUserMessages);
    }

    public static Optional<String> bestPathFromConversation(String currentMessage, List<String> priorUserMessages) {
        Optional<String> best = bestPath(currentMessage);
        if (priorUserMessages == null || priorUserMessages.isEmpty()) {
            return best;
        }
        for (int i = priorUserMessages.size() - 1; i >= 0; i--) {
            Optional<String> prior = bestPath(priorUserMessages.get(i));
            if (prior.isPresent() && (best.isEmpty() || prior.get().length() > best.get().length())) {
                best = prior;
            }
        }
        return best;
    }

    /** 是否为临时清理白名单下的子目录（允许整目录删除，禁止直接删 Temp 根本身）。 */
    public static boolean isCleanableSubDirectory(OpsPathPolicy policy, String path) {
        if (policy == null || path == null || path.isBlank()) {
            return false;
        }
        if (!policy.isAllowedCleanDirectory(path)) {
            return false;
        }
        if (OsRuntime.isWindows()) {
            String norm = OpsPathPolicy.normalizeWindowsPath(path);
            for (String root : policy.snapshotTempCleanRoots()) {
                String rx = OpsPathPolicy.normalizeWindowsPath(root);
                if (rx.isEmpty()) {
                    continue;
                }
                if (norm.equalsIgnoreCase(rx)) {
                    return false;
                }
                String prefix = rx.toLowerCase() + "/";
                if (norm.toLowerCase().startsWith(prefix) && norm.length() > rx.length() + 1) {
                    return true;
                }
            }
            return false;
        }
        String norm = policy.normalizeUnixPath(path);
        for (String root : policy.snapshotTempCleanRoots()) {
            String rt = policy.normalizeUnixPath(root);
            if (norm.equals(rt)) {
                return false;
            }
            if (norm.startsWith(rt + "/") && norm.length() > rt.length() + 1) {
                return true;
            }
        }
        return false;
    }

    private static List<String> collectPathCandidates(String text) {
        List<String> candidates = new ArrayList<>();
        Matcher win = WINDOWS_PATH.matcher(text);
        while (win.find()) {
            candidates.add(normalizeCandidate(win.group(1)));
        }
        if (candidates.isEmpty()) {
            Matcher unix = UNIX_PATH.matcher(text);
            while (unix.find()) {
                candidates.add(normalizeCandidate(unix.group(1)));
            }
        }
        return candidates;
    }

    private static String normalizeCandidate(String raw) {
        if (raw == null) {
            return "";
        }
        String t = raw.trim();
        while (t.endsWith(".") || t.endsWith(",") || t.endsWith(")") || t.endsWith("」") || t.endsWith("\"")) {
            t = t.substring(0, t.length() - 1).trim();
        }
        return t;
    }
}
