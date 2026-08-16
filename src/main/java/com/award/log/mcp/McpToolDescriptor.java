package com.award.log.mcp;

/**
 * MCP 工具元数据（由 {@link McpToolCatalog} 统一维护）。
 */
public record McpToolDescriptor(
        String toolName,
        String beanClassName,
        boolean httpAllowed,
        double defaultRiskScore,
        boolean readOnlyObservation
) {
    public double resolvedRiskScore(double fallback) {
        return defaultRiskScore >= 0 ? defaultRiskScore : fallback;
    }
}
