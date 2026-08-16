package com.award.log.mcp.dispatch;

import java.util.Map;
import java.util.regex.Pattern;

public final class McpToolParamReader {

    private static final Pattern REAL_WRITE_UTTERANCE = Pattern.compile(
            "真实删除|彻底删除|真正删除|不要预览|不要dry-?run|执行删除|真实重启|立即重启|直接删除|立即删除|马上删除|确认执行|开始清理|执行修复|删掉",
            Pattern.CASE_INSENSITIVE);

    private McpToolParamReader() {
    }

    public static String getString(Map<String, Object> params, String key) {
        if (params == null || !params.containsKey(key)) {
            return null;
        }
        Object value = params.get(key);
        return value != null ? value.toString() : null;
    }

    public static Integer getInteger(Map<String, Object> params, String key) {
        if (params == null || !params.containsKey(key)) {
            return null;
        }
        Object value = params.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static Double getDouble(Map<String, Object> params, String key) {
        if (params == null || !params.containsKey(key)) {
            return null;
        }
        Object value = params.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return Double.parseDouble(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static Long getLong(Map<String, Object> params, String key) {
        if (params == null || !params.containsKey(key)) {
            return null;
        }
        Object value = params.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(value.toString().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static Boolean getBoolean(Map<String, Object> params, String key) {
        if (params == null || !params.containsKey(key)) {
            return null;
        }
        Object value = params.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        return Boolean.parseBoolean(value.toString());
    }

    /** 用户已在界面点击「确认执行」后强制开启写参数（不依赖 userMessage 关键词）。 */
    public static void forceConfirmedWriteToolParams(String toolName, Map<String, Object> parameters) {
        if (toolName == null || parameters == null) {
            return;
        }
        switch (toolName) {
            case "CleanTempTool" -> {
                parameters.put("dryRun", false);
                parameters.put("confirmDelete", true);
                String path = getString(parameters, "path");
                if (path != null && !path.isBlank() && isTempSubDirectoryPath(path)) {
                    parameters.put("removeDirectory", true);
                    parameters.put("days", 0);
                }
            }
            case "LogCleanupTool" -> {
                parameters.put("dryRun", false);
                parameters.put("confirmDelete", true);
            }
            case "DiskOpsTool" -> {
                if (operationIs(parameters, "clean-temp", "clean", "cleanup")) {
                    parameters.put("dryRun", false);
                    parameters.put("confirmDelete", true);
                }
            }
            case "LogOpsTool" -> {
                if (operationIs(parameters, "cleanup", "clean", "prune")) {
                    parameters.put("dryRun", false);
                    parameters.put("confirmDelete", true);
                }
            }
            case "ServiceRestartTool", "ServiceOpsTool", "SystemdTool" -> {
                if (toolName.equals("ServiceRestartTool")
                        || toolName.equals("SystemdTool")
                        || operationIs(parameters, "restart", "reload")) {
                    parameters.put("dryRun", false);
                    parameters.put("confirmRestart", true);
                    if (toolName.equals("SystemdTool") || toolName.equals("ServiceOpsTool")) {
                        if (!parameters.containsKey("operation") || String.valueOf(parameters.get("operation")).isBlank()) {
                            parameters.put("operation", "restart");
                        }
                    }
                }
            }
            case "ContainerOpsTool", "DockerTool" -> applyContainerWriteConfirm(parameters);
            case "ProcessOpsTool", "ProcessTool" -> {
                boolean killOp = toolName.equals("ProcessOpsTool")
                        || operationIs(parameters, "kill", "terminate", "stop")
                        || parameters.get("pid") != null;
                if (killOp) {
                    parameters.put("dryRun", false);
                    parameters.put("confirmKill", true);
                    if (toolName.equals("ProcessTool") || toolName.equals("ProcessOpsTool")) {
                        if (!parameters.containsKey("operation") || String.valueOf(parameters.get("operation")).isBlank()) {
                            parameters.put("operation", "kill");
                        }
                    }
                }
            }
            default -> { }
        }
    }

    public static void applyConfirmedWriteToolParams(String toolName, Map<String, Object> parameters) {
        applyConfirmedWriteToolParams(toolName, parameters, null);
    }

    /**
     * 二次确认后仅在用户已请求真实写入时关闭 dryRun；预览类请求保持 dryRun=true。
     */
    public static void applyConfirmedWriteToolParams(String toolName, Map<String, Object> parameters, String userMessage) {
        if (toolName == null || parameters == null) {
            return;
        }
        if (!shouldEscalateToRealWrite(parameters, userMessage)) {
            return;
        }
        forceConfirmedWriteToolParams(toolName, parameters);
    }

    public static boolean shouldEscalateToRealWrite(Map<String, Object> parameters, String userMessage) {
        if (parameters != null) {
            if (Boolean.TRUE.equals(getBoolean(parameters, "confirmDelete"))
                    || Boolean.TRUE.equals(getBoolean(parameters, "confirmRestart"))
                    || Boolean.TRUE.equals(getBoolean(parameters, "confirmKill"))
                    || Boolean.TRUE.equals(getBoolean(parameters, "confirmStop"))) {
                return true;
            }
            if (Boolean.FALSE.equals(getBoolean(parameters, "dryRun"))) {
                return true;
            }
        }
        if (userMessage == null || userMessage.isBlank()) {
            return false;
        }
        return REAL_WRITE_UTTERANCE.matcher(userMessage).find();
    }

    private static boolean isTempSubDirectoryPath(String path) {
        String norm = path.trim().replace('\\', '/');
        if (norm.equals("/tmp") || norm.equals("/var/tmp") || norm.equals("/var/temp")) {
            return false;
        }
        return norm.startsWith("/tmp/") || norm.startsWith("/var/tmp/") || norm.startsWith("/var/temp/");
    }

    private static boolean operationIs(Map<String, Object> parameters, String... ops) {
        String o = getString(parameters, "operation");
        if (o == null || o.isBlank()) {
            return false;
        }
        String lower = o.trim().toLowerCase();
        for (String op : ops) {
            if (op.equals(lower)) {
                return true;
            }
        }
        return false;
    }

    private static void applyContainerWriteConfirm(Map<String, Object> parameters) {
        if (parameters == null) {
            return;
        }
        String op = getString(parameters, "operation");
        if (op == null) {
            return;
        }
        String lower = op.trim().toLowerCase();
        if ("restart".equals(lower)) {
            parameters.put("dryRun", false);
            parameters.put("confirmRestart", true);
        } else if ("stop".equals(lower)) {
            parameters.put("dryRun", false);
            parameters.put("confirmStop", true);
        }
    }
}
