package com.award.log.security;

/**
 * 管理面路径识别：用于将高权限配置、MCP、巡检监控与运行态接口限制到管理端口。
 */
public final class ManagementAccessPaths {

    private ManagementAccessPaths() {
    }

    public static boolean isManagementPath(String path) {
        if (path == null || path.isBlank()) {
            return false;
        }
        return path.startsWith("/admin/")
                || path.startsWith("/api/mcp/")
                || path.startsWith("/api/assistant/")
                || path.startsWith("/api/security/")
                || path.startsWith("/api/system-config/")
                || path.startsWith("/api/agent/path-policy")
                || path.startsWith("/api/runbook/")
                || path.startsWith("/api/ops/")
                || path.startsWith("/api/ops-")
                || path.startsWith("/api/ops-trace/")
                || path.startsWith("/api/performance/")
                || path.startsWith("/api/collector/")
                || path.startsWith("/api/kafka/")
                || path.startsWith("/api/v1/model/")
                || equalsPath(path, "/api/platform/info")
                || equalsPath(path, "/api/platform/readiness")
                || equalsPath(path, "/api/platform/backend-probe")
                || equalsPath(path, "/doc.html")
                || path.startsWith("/swagger-ui")
                || path.startsWith("/v3/api-docs")
                || path.startsWith("/webjars/")
                || path.startsWith("/actuator")
                || equalsPath(path, "/ws/performance")
                || equalsPath(path, "/ws/logs");
    }

    public static boolean isBusinessPath(String path) {
        return !isManagementPath(path);
    }

    private static boolean equalsPath(String path, String expected) {
        return expected.equals(path);
    }
}
