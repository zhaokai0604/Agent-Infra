package com.award.log.util;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** 解析 Linux os-release 规范，兼容麒麟扩展字段与未加引号值。 */
public final class OsReleaseParser {

    private OsReleaseParser() {
    }

    public static Map<String, String> parse(String content) {
        Map<String, String> values = new LinkedHashMap<>();
        if (content == null || content.isBlank()) {
            return values;
        }
        for (String raw : content.split("\\R")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int equals = line.indexOf('=');
            if (equals <= 0) {
                continue;
            }
            String key = line.substring(0, equals).trim();
            if (!key.matches("[A-Za-z0-9_]+")) {
                continue;
            }
            String value = line.substring(equals + 1).trim();
            values.put(key, unquote(value));
        }
        return values;
    }

    public static boolean isKylin(Map<String, String> values) {
        if (values == null || values.isEmpty()) {
            return false;
        }
        String joined = String.join(" ", values.values()).toLowerCase(Locale.ROOT);
        return joined.contains("kylin")
                || joined.contains("neokylin")
                || joined.contains("openkylin")
                || joined.contains("银河麒麟")
                || joined.contains("麒麟");
    }

    private static String unquote(String value) {
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                value = value.substring(1, value.length() - 1);
            }
        }
        return value.replace("\\\\", "\\").replace("\\\"", "\"");
    }
}
