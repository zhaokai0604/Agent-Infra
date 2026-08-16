package com.award.log.security.effect;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * 单次工具调用的效果对象：动作、目标、不可逆等级与证据契约键。
 */
public record ToolEffect(
        EffectAction action,
        String targetType,
        String targetId,
        int irreversibility,
        boolean writeEffect,
        String evidenceContractId
) {

    public ToolEffect {
        Objects.requireNonNull(action, "action");
        targetType = targetType == null || targetType.isBlank() ? "NONE" : targetType.trim().toUpperCase(Locale.ROOT);
        targetId = targetId == null ? "" : targetId.trim();
        irreversibility = Math.max(0, Math.min(10, irreversibility));
        evidenceContractId = evidenceContractId == null ? "" : evidenceContractId.trim();
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("action", action.name());
        m.put("targetType", targetType);
        m.put("targetId", targetId);
        m.put("irreversibility", irreversibility);
        m.put("writeEffect", writeEffect);
        m.put("evidenceContractId", evidenceContractId);
        return m;
    }

    public static ToolEffect observe(String targetType, String targetId) {
        return new ToolEffect(EffectAction.OBSERVE, targetType, targetId, 0, false, "");
    }
}
