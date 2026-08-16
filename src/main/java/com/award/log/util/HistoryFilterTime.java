package com.award.log.util;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 历史任务列表时间筛选：将前端 YYYY-MM-DD / ISO 字符串转为闭区间 [start, end]。
 */
public final class HistoryFilterTime {

    private static final Pattern DATE_PREFIX = Pattern.compile("(\\d{4}-\\d{2}-\\d{2})");

    private HistoryFilterTime() {
    }

    public static LocalDateTime parseStart(String raw) {
        LocalDate d = parseDateOnly(raw);
        return d == null ? null : d.atStartOfDay();
    }

    /** 结束日 23:59:59（闭区间，覆盖当天全部任务） */
    public static LocalDateTime parseEnd(String raw) {
        LocalDate d = parseDateOnly(raw);
        return d == null ? null : d.atTime(LocalTime.of(23, 59, 59));
    }

    public static LocalDate parseDateOnly(String raw) {
        if (raw == null) {
            return null;
        }
        String t = raw.trim();
        if (t.isEmpty()) {
            return null;
        }
        Matcher m = DATE_PREFIX.matcher(t);
        if (m.find()) {
            t = m.group(1);
        } else {
            return null;
        }
        try {
            return LocalDate.parse(t, DateTimeFormatter.ISO_LOCAL_DATE);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
