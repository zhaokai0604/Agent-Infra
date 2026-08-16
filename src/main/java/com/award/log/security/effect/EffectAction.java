package com.award.log.security.effect;

/**
 * 工具调用归一后的副作用类型（门控按效果裁决，而非仅看指令文本）。
 */
public enum EffectAction {
    OBSERVE,
    DELETE,
    TRUNCATE,
    RESTART,
    KILL,
    MUTATE_CONFIG,
    UNKNOWN_WRITE
}
