package com.award.log.model.patrol;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 巡检线索（强类型，避免 Map 字符串 key 拼写错误）。
 */
public record PatrolFinding(String level, String code, String title, String detail) {

    public static PatrolFinding of(String level, String code, String title, String detail) {
        return new PatrolFinding(level, code, title, detail);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("level", level);
        m.put("code", code);
        m.put("title", title);
        m.put("detail", detail);
        return m;
    }

    public static PatrolFinding fromMap(Map<String, Object> m) {
        if (m == null) {
            return null;
        }
        return new PatrolFinding(
                str(m.get("level")),
                str(m.get("code")),
                str(m.get("title")),
                str(m.get("detail")));
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }
}
