package com.award.log.agent;

import com.award.log.security.McpToolSurface;

import java.util.Locale;
import java.util.Set;

/**
 * ChatAgent 工具挂载阶段：未确认写时强制只读面，确认后才挂载写类工具。
 */
public final class AgentToolPhase {

    /**
     * 诊断阶段额外剔除的 Bean（含混合读写 Bean，避免模型在未确认时调用写方法）。
     * 与 {@link com.award.log.security.ReadOnlySurfaceDenylist} 叠加。
     */
    private static final Set<String> DIAGNOSE_EXTRA_DENIED = Set.of(
            "DockerTool",
            "SystemdTool",
            "AutonomousOpsTool",
            "FirewallTool",
            "PrivilegeTool"
    );

    private AgentToolPhase() {
    }

    /**
     * 会话已是只读面则保持只读；否则未确认写时强制 READ_ONLY，确认后用请求面。
     */
    public static McpToolSurface effectiveSurface(McpToolSurface requested, boolean allowWrite) {
        if (requested == McpToolSurface.READ_ONLY) {
            return McpToolSurface.READ_ONLY;
        }
        if (!allowWrite) {
            return McpToolSurface.READ_ONLY;
        }
        return requested == null ? McpToolSurface.FULL : requested;
    }

    public static boolean denyBeanInDiagnosePhase(String beanSimpleName) {
        if (beanSimpleName == null || beanSimpleName.isBlank()) {
            return false;
        }
        return DIAGNOSE_EXTRA_DENIED.contains(beanSimpleName.trim());
    }

    public static boolean writeToolsMounted(boolean allowWrite, McpToolSurface effective) {
        return allowWrite && effective != McpToolSurface.READ_ONLY;
    }

    public static String phaseHintZh(boolean allowWrite, boolean hasPendingWrite) {
        if (allowWrite) {
            return "已挂载写工具，按确认计划落地（仍受白名单约束）";
        }
        if (hasPendingWrite) {
            return "本轮仅挂载观测工具；写步骤待「确认执行」后挂载";
        }
        return "本轮仅挂载观测工具";
    }

    public static Set<String> diagnoseExtraDeniedSnapshot() {
        return DIAGNOSE_EXTRA_DENIED;
    }

    public static String normalizeBean(String name) {
        return name == null ? "" : name.trim();
    }

    public static boolean isDiagnosePhase(String planPhase) {
        if (planPhase == null) {
            return true;
        }
        String p = planPhase.trim().toUpperCase(Locale.ROOT);
        return !"EXECUTE".equals(p);
    }
}
