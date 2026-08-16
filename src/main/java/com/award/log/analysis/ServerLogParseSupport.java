package com.award.log.analysis;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 服务器侧常见中间件/数据库日志时间格式（与 Linux 容器解析互补）。
 */
public final class ServerLogParseSupport {

    /** Nginx error：2024/02/10 14:22:01 [error] … */
    private static final Pattern NGINX_ERROR_HEAD = Pattern.compile(
            "\\b(\\d{4})/(\\d{1,2})/(\\d{1,2})\\s+(\\d{2}):(\\d{2}):(\\d{2})\\b");

    /** Tomcat / Java 常见：10-Feb-2024 14:22:01.123 */
    private static final Pattern TOMCAT_STAMP = Pattern.compile(
            "\\b(\\d{1,2}-[A-Za-z]{3}-\\d{4}\\s+\\d{2}:\\d{2}:\\d{2}(?:\\.\\d{1,3})?)\\b");

    /** PostgreSQL / MySQL 错误行：2024-02-10 14:22:01.123 UTC|CST … */
    private static final Pattern RDBMS_TIMESTAMP = Pattern.compile(
            "\\b(\\d{4}-\\d{2}-\\d{2}\\s+\\d{2}:\\d{2}:\\d{2}(?:\\.\\d{1,6})?)\\s*(?:UTC|GMT|CST|PST|EST|CET|JST|Asia/\\w+)?");

    /** Redis 4.x：12345:M 10 Feb 2024 14:22:01.123 * … */
    private static final Pattern REDIS_MASTER = Pattern.compile(
            ":\\d+\\s+[MF]\\s+(\\d{1,2})\\s+([A-Za-z]{3})\\s+(\\d{4})\\s+(\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?)");

    /** HAProxy http 模式 access / 部分 error（无 PRI 时） */
    private static final Pattern HAPROXY_HTTP_TS = Pattern.compile(
            "\\[(\\d{2}/[A-Za-z]{3}/\\d{4}:\\d{2}:\\d{2}:\\d{2}\\.[0-9]+)\\]");

    private static final DateTimeFormatter APACHE_LIKE = DateTimeFormatter.ofPattern("dd/MMM/yyyy:HH:mm:ss.SSS", Locale.ENGLISH);

    private ServerLogParseSupport() {
    }

    public static LocalDateTime tryAll(String line) {
        if (line == null || line.isBlank()) {
            return null;
        }
        LocalDateTime t = tryNginxErrorStyle(line);
        if (t != null) {
            return t;
        }
        t = tryTomcatStyle(line);
        if (t != null) {
            return t;
        }
        t = tryRdbmsLine(line);
        if (t != null) {
            return t;
        }
        t = tryRedisLine(line);
        if (t != null) {
            return t;
        }
        return tryHaproxyBracket(line);
    }

    public static LocalDateTime tryNginxErrorStyle(String line) {
        Matcher m = NGINX_ERROR_HEAD.matcher(line);
        if (!m.find()) {
            return null;
        }
        try {
            return LocalDateTime.of(
                    Integer.parseInt(m.group(1)),
                    Integer.parseInt(m.group(2)),
                    Integer.parseInt(m.group(3)),
                    Integer.parseInt(m.group(4)),
                    Integer.parseInt(m.group(5)),
                    Integer.parseInt(m.group(6)));
        } catch (Exception e) {
            return null;
        }
    }

    public static LocalDateTime tryTomcatStyle(String line) {
        Matcher m = TOMCAT_STAMP.matcher(line);
        if (!m.find()) {
            return null;
        }
        String raw = m.group(1);
        for (DateTimeFormatter fmt : new DateTimeFormatter[]{
                DateTimeFormatter.ofPattern("dd-MMM-yyyy HH:mm:ss.SSS", Locale.ENGLISH),
                DateTimeFormatter.ofPattern("d-MMM-yyyy HH:mm:ss.SSS", Locale.ENGLISH),
                DateTimeFormatter.ofPattern("dd-MMM-yyyy HH:mm:ss", Locale.ENGLISH),
                DateTimeFormatter.ofPattern("d-MMM-yyyy HH:mm:ss", Locale.ENGLISH)
        }) {
            try {
                return LocalDateTime.parse(raw, fmt);
            } catch (DateTimeParseException ignored) {
            }
        }
        return null;
    }

    public static LocalDateTime tryRdbmsLine(String line) {
        Matcher m = RDBMS_TIMESTAMP.matcher(line);
        if (!m.find()) {
            return null;
        }
        String raw = m.group(1).trim();
        for (String pat : new String[]{
                "yyyy-MM-dd HH:mm:ss.SSSSSS",
                "yyyy-MM-dd HH:mm:ss.SSS",
                "yyyy-MM-dd HH:mm:ss"
        }) {
            try {
                return LocalDateTime.parse(raw, DateTimeFormatter.ofPattern(pat));
            } catch (DateTimeParseException ignored) {
            }
        }
        return null;
    }

    public static LocalDateTime tryRedisLine(String line) {
        Matcher m = REDIS_MASTER.matcher(line);
        if (!m.find()) {
            return null;
        }
        try {
            String dayMonYear = m.group(1) + " " + m.group(2) + " " + m.group(3);
            LocalDate d = LocalDate.parse(dayMonYear, DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH));
            String tpart = m.group(4);
            LocalTime tt;
            if (tpart.contains(".")) {
                tt = LocalTime.parse(tpart, DateTimeFormatter.ofPattern("HH:mm:ss.SSS"));
            } else {
                tt = LocalTime.parse(tpart, DateTimeFormatter.ofPattern("HH:mm:ss"));
            }
            return LocalDateTime.of(d, tt);
        } catch (Exception e) {
            return null;
        }
    }

    public static LocalDateTime tryHaproxyBracket(String line) {
        Matcher m = HAPROXY_HTTP_TS.matcher(line);
        if (!m.find()) {
            return null;
        }
        try {
            return LocalDateTime.parse(m.group(1), APACHE_LIKE);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** 协议粗分类：Web/反代/应用服务器典型行 */
    public static boolean looksLikeWebMiddlewareServer(String line) {
        if (line == null || line.length() < 12) {
            return false;
        }
        String lower = line.toLowerCase(Locale.ROOT);
        if (NGINX_ERROR_HEAD.matcher(line).find() && line.contains("[")) {
            return true;
        }
        if (TOMCAT_STAMP.matcher(line).find() && (lower.contains("catalina")
                || lower.contains("tomcat") || lower.contains("localhost-startstop")
                || lower.contains("localhost.") || lower.contains("org.apache."))) {
            return true;
        }
        if (lower.contains("haproxy") && HAPROXY_HTTP_TS.matcher(line).find()) {
            return true;
        }
        if (lower.contains("openresty") || lower.contains("ingress nginx")) {
            return true;
        }
        if (lower.contains("undertow") || lower.contains("jetty") && lower.contains("http")) {
            return true;
        }
        return false;
    }

    /** 数据库 / 缓存守护进程行（便于与 JDBC 应用日志区分） */
    public static boolean looksLikeDbOrCacheServerDaemon(String line) {
        if (line == null) {
            return false;
        }
        String lower = line.toLowerCase(Locale.ROOT);
        if (RDBMS_TIMESTAMP.matcher(line).find()
                && (lower.contains("[error]") || lower.contains("fatal:")
                || lower.contains("postgresql") || lower.contains("mysqld")
                || lower.contains("innodb") || lower.contains("mongo")
                || lower.contains("checkpoint") || lower.contains("wal "))) {
            return true;
        }
        if (REDIS_MASTER.matcher(line).find() || (lower.contains("redis ") && lower.contains("version"))) {
            return true;
        }
        return false;
    }
}
