package com.award.log.governance;

/**
 * 资产分级：决定动作是否允许自动执行。
 */
public enum AssetTier {
    /** 核心有状态（DB、控制面等）— 禁止任何自动写操作 */
    CORE_STATEFUL,
    /** 核心无状态 — 仅允许审批/确认后执行 */
    CORE_STATELESS,
    /** 非核心 — 低风险动作可自动（仍受 dry-run / 风险分 / 冷却约束） */
    NON_CORE,
    /** 禁止自动 — 仅人工 Runbook */
    FORBIDDEN_AUTO;

    public static AssetTier parse(String raw, AssetTier fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return AssetTier.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return fallback;
        }
    }
}
