package com.award.log.security.effect;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * 效果指纹：绑定 tool + 归一化参数 + 效果语义，防止确认后换参执行。
 */
public final class EffectFingerprint {

    private EffectFingerprint() {
    }

    public static String of(String toolName, Map<String, Object> parameters, ToolEffect effect) {
        StringBuilder sb = new StringBuilder();
        sb.append("tool=").append(normalize(toolName));
        sb.append("|action=").append(effect == null ? "?" : effect.action().name());
        sb.append("|targetType=").append(effect == null ? "?" : effect.targetType());
        sb.append("|targetId=").append(effect == null ? "" : effect.targetId());
        sb.append("|params=");
        sb.append(canonicalParams(parameters));
        return sha256Hex(sb.toString());
    }

    static String canonicalParams(Map<String, Object> parameters) {
        if (parameters == null || parameters.isEmpty()) {
            return "{}";
        }
        TreeMap<String, String> sorted = new TreeMap<>();
        for (Map.Entry<String, Object> e : parameters.entrySet()) {
            if (e.getKey() == null) {
                continue;
            }
            String key = e.getKey().trim();
            // 确认态强制改写字段不参与指纹，避免 dryRun 预览与真实写指纹不一致
            if (isWriteToggleKey(key)) {
                continue;
            }
            sorted.put(key, stringify(e.getValue()));
        }
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> e : sorted.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        sb.append('}');
        return sb.toString();
    }

    private static boolean isWriteToggleKey(String key) {
        String k = key.toLowerCase(Locale.ROOT);
        return "dryrun".equals(k)
                || "confirmdelete".equals(k)
                || "confirmrestart".equals(k)
                || "confirmkill".equals(k)
                || "confirmstop".equals(k)
                || "forceconfirmed".equals(k)
                || "forceremediate".equals(k);
    }

    private static String stringify(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                copy.put(String.valueOf(e.getKey()), e.getValue());
            }
            return canonicalParams(copy);
        }
        if (value instanceof List<?> list) {
            List<String> parts = new ArrayList<>(list.size());
            for (Object item : list) {
                parts.add(stringify(item));
            }
            return parts.toString();
        }
        return String.valueOf(value).trim();
    }

    private static String normalize(String toolName) {
        return toolName == null ? "" : toolName.trim();
    }

    private static String sha256Hex(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            return Integer.toHexString(raw.hashCode());
        }
    }
}
