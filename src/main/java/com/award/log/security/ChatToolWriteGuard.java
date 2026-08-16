package com.award.log.security;

import java.util.Locale;
import java.util.Map;

/**
 * 对话 ChatClient {@code @Tool} 路径：禁止真实写操作（仅预览），须走 HTTP MCP 二次确认。
 */
public final class ChatToolWriteGuard {

    private ChatToolWriteGuard() {
    }

    public static boolean requestsRealMutation(String toolBeanName, Map<String, Object> parameters) {
        if (parameters == null || parameters.isEmpty()) {
            return false;
        }
        if (Boolean.TRUE.equals(coerceBool(parameters.get("forceRemediate")))) {
            return true;
        }
        if (Boolean.TRUE.equals(coerceBool(parameters.get("confirmDelete")))
                || Boolean.TRUE.equals(coerceBool(parameters.get("confirmRestart")))
                || Boolean.TRUE.equals(coerceBool(parameters.get("confirmKill")))
                || Boolean.TRUE.equals(coerceBool(parameters.get("confirmStop")))) {
            return true;
        }
        if (Boolean.FALSE.equals(coerceBool(parameters.get("dryRun")))) {
            return true;
        }
        String op = stringVal(parameters.get("operation"));
        if (!op.isEmpty() && isWriteOperation(op) && !Boolean.TRUE.equals(coerceBool(parameters.get("dryRun")))) {
            return true;
        }
        return false;
    }

    public static String blockMessage(String toolBeanName) {
        return "对话入口不支持真实写操作（" + toolBeanName
                + "）。请使用侧栏「工具箱」执行，并在界面二次确认后再落地；对话内仅允许预览/只读观测。";
    }

    private static boolean isWriteOperation(String operation) {
        String o = operation.toLowerCase(Locale.ROOT);
        return o.contains("clean") || o.contains("delete") || o.contains("restart")
                || o.contains("stop") || o.contains("kill") || o.contains("remediat");
    }

    private static Boolean coerceBool(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Boolean b) {
            return b;
        }
        String s = String.valueOf(v).trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty()) {
            return null;
        }
        if ("true".equals(s) || "1".equals(s) || "yes".equals(s)) {
            return true;
        }
        if ("false".equals(s) || "0".equals(s) || "no".equals(s)) {
            return false;
        }
        return null;
    }

    private static String stringVal(Object v) {
        return v == null ? "" : String.valueOf(v).trim();
    }
}
