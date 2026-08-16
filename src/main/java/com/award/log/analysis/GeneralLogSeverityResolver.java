package com.award.log.analysis;

import com.award.log.model.LogSeverityLevel;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 面向混合来源（应用 / 系统 / 设备 / 通用文本）的等级推断：
 * 优先解析显式等级字段与常见日志框架格式，再用保守关键词兜底，避免「类名含 error」等大量误升 ERROR。
 */
public final class GeneralLogSeverityResolver {

    /** 参与结构化解析的行前缀长度，避免在堆栈深处误匹配 token */
    private static final int HEAD_CHARS = 420;

    private static final Pattern LEVEL_KV = Pattern.compile(
            "(?i)level[\"']?\\s*[=:]\\s*[\"']?(fatal|critical|error|err|warn|warning|info|debug|trace)");

    private static final Pattern BRACKET_THEN_LEVEL = Pattern.compile(
            "(?i)\\]\\s*(ERROR|WARN|WARNING|INFO|DEBUG|TRACE|FATAL|CRITICAL)\\s");

    /**
     * 行首或空白后的独立等级词（Java Logback/Log4j2 常见）
     */
    private static final Pattern DELIMITED_LEVEL_HEAD = Pattern.compile(
            "(?i)(?:^|\\s)(ERROR|WARN|WARNING|INFO|DEBUG|TRACE|FATAL|CRITICAL)(?=\\s|$|[\\[,:;])");

    /** RFC5424 syslog 行首 PRI：&lt;facility*8+severity&gt;，severity 为低 3 位 */
    private static final Pattern SYSLOG_PRI_HEAD = Pattern.compile("^<([0-9]{1,3})>\\s*");

    private GeneralLogSeverityResolver() {
    }

    public static LogSeverityLevel resolve(String logLine) {
        if (logLine == null || logLine.isBlank()) {
            return LogSeverityLevel.UNKNOWN_LEVEL;
        }
        String line = logLine;
        String head = line.length() > HEAD_CHARS ? line.substring(0, HEAD_CHARS) : line;
        String lower = line.toLowerCase(Locale.ROOT);
        String lowerHead = head.toLowerCase(Locale.ROOT);

        Matcher priM = SYSLOG_PRI_HEAD.matcher(line);
        if (priM.find()) {
            try {
                int pri = Integer.parseInt(priM.group(1));
                if (pri >= 0 && pri <= 191) {
                    LogSeverityLevel fromPri = mapSyslogPriSeverity(pri & 7);
                    if (fromPri != LogSeverityLevel.UNKNOWN_LEVEL) {
                        return fromPri;
                    }
                }
            } catch (NumberFormatException ignored) {
                // continue
            }
        }

        // Nginx / OpenResty / HAProxy 文本：[emerg] [error] [warn] …
        Matcher ngxHa = Pattern.compile("(?i)\\[(emerg|alert|crit|error|warn|notice|info|debug)\\]").matcher(head);
        if (ngxHa.find()) {
            LogSeverityLevel nl = mapNginxStyleBracket(ngxHa.group(1).toLowerCase(Locale.ROOT));
            if (nl != LogSeverityLevel.UNKNOWN_LEVEL) {
                return nl;
            }
        }
        // Tomcat / JUL：[SEVERE] [WARNING] [INFO] …
        Matcher jul = Pattern.compile("(?i)\\[(severe|warning|info|config|fine|finer|finest)\\]").matcher(head);
        if (jul.find()) {
            LogSeverityLevel jl = mapJulBracket(jul.group(1).toLowerCase(Locale.ROOT));
            if (jl != LogSeverityLevel.UNKNOWN_LEVEL) {
                return jl;
            }
        }

        // 1) Windows 事件查看器导出：等级在行首
        if (line.startsWith("关键")) {
            return LogSeverityLevel.FATAL_LEVEL;
        }
        if (line.startsWith("错误")) {
            return LogSeverityLevel.ERROR_LEVEL;
        }
        if (line.startsWith("警告")) {
            return LogSeverityLevel.WARNING_LEVEL;
        }
        if (line.startsWith("信息")) {
            return LogSeverityLevel.INFO_LEVEL;
        }
        if (line.startsWith("详细")) {
            return LogSeverityLevel.DEBUG_LEVEL;
        }

        // 2) level= / JSON 风格
        LogSeverityLevel fromKv = mapLevelToken(matchFirstGroup(LEVEL_KV.matcher(lowerHead)));
        if (fromKv != LogSeverityLevel.UNKNOWN_LEVEL) {
            return fromKv;
        }

        // 3) ] ERROR / ] WARN（线程名后）
        LogSeverityLevel fromBracket = mapLevelTokenUpper(matchFirstGroup(BRACKET_THEN_LEVEL.matcher(head)));
        if (fromBracket != LogSeverityLevel.UNKNOWN_LEVEL) {
            return fromBracket;
        }

        // 4) 头部独立等级词（优先第一次出现）
        Matcher delim = DELIMITED_LEVEL_HEAD.matcher(head);
        if (delim.find()) {
            LogSeverityLevel t = mapLevelTokenUpper(delim.group(1));
            if (t != LogSeverityLevel.UNKNOWN_LEVEL) {
                return t;
            }
        }

        // 5) 保守兜底：不用裸 substring("error")，避免类名/路径误伤
        return matchConservativeFallback(lower);
    }

    private static String matchFirstGroup(Matcher m) {
        return m.find() ? m.group(1) : null;
    }

    private static LogSeverityLevel mapLevelToken(String token) {
        if (token == null) {
            return LogSeverityLevel.UNKNOWN_LEVEL;
        }
        String t = token.toLowerCase(Locale.ROOT);
        if ("fatal".equals(t) || "critical".equals(t)) {
            return LogSeverityLevel.FATAL_LEVEL;
        }
        if ("error".equals(t) || "err".equals(t)) {
            return LogSeverityLevel.ERROR_LEVEL;
        }
        if ("warn".equals(t) || "warning".equals(t)) {
            return LogSeverityLevel.WARNING_LEVEL;
        }
        if ("info".equals(t)) {
            return LogSeverityLevel.INFO_LEVEL;
        }
        if ("debug".equals(t) || "trace".equals(t)) {
            return LogSeverityLevel.DEBUG_LEVEL;
        }
        return LogSeverityLevel.UNKNOWN_LEVEL;
    }

    private static LogSeverityLevel mapLevelTokenUpper(String token) {
        return token == null ? LogSeverityLevel.UNKNOWN_LEVEL : mapLevelToken(token.toLowerCase(Locale.ROOT));
    }

    /** Nginx / HAProxy 风格 [level] */
    private static LogSeverityLevel mapNginxStyleBracket(String s) {
        return switch (s) {
            case "emerg", "alert" -> LogSeverityLevel.FATAL_LEVEL;
            case "crit" -> LogSeverityLevel.FATAL_LEVEL;
            case "error" -> LogSeverityLevel.ERROR_LEVEL;
            case "warn" -> LogSeverityLevel.WARNING_LEVEL;
            case "notice", "info" -> LogSeverityLevel.INFO_LEVEL;
            case "debug" -> LogSeverityLevel.DEBUG_LEVEL;
            default -> LogSeverityLevel.UNKNOWN_LEVEL;
        };
    }

    private static LogSeverityLevel mapJulBracket(String s) {
        return switch (s) {
            case "severe" -> LogSeverityLevel.FATAL_LEVEL;
            case "warning" -> LogSeverityLevel.WARNING_LEVEL;
            case "info" -> LogSeverityLevel.INFO_LEVEL;
            case "config", "fine", "finer", "finest" -> LogSeverityLevel.DEBUG_LEVEL;
            default -> LogSeverityLevel.UNKNOWN_LEVEL;
        };
    }

    /** RFC5424 severity：0 Emerg … 7 Debug */
    private static LogSeverityLevel mapSyslogPriSeverity(int sev) {
        return switch (sev) {
            case 0, 1, 2 -> LogSeverityLevel.FATAL_LEVEL;
            case 3 -> LogSeverityLevel.ERROR_LEVEL;
            case 4 -> LogSeverityLevel.WARNING_LEVEL;
            case 5, 6 -> LogSeverityLevel.INFO_LEVEL;
            case 7 -> LogSeverityLevel.DEBUG_LEVEL;
            default -> LogSeverityLevel.UNKNOWN_LEVEL;
        };
    }

    private static LogSeverityLevel matchConservativeFallback(String lower) {
        if (Pattern.compile("(?i)\\bfatal\\b").matcher(lower).find()
                || lower.contains("致命")
                || lower.contains("panic")
                || lower.contains("kernel panic")
                || lower.contains("segmentation fault")
                || lower.contains("sigsegv")) {
            return LogSeverityLevel.FATAL_LEVEL;
        }
        if (lower.contains("oom-killer")
                || lower.contains("out of memory: kill")
                || lower.contains("killed process")
                || lower.contains("cannot allocate memory")) {
            return LogSeverityLevel.ERROR_LEVEL;
        }
        if (lower.contains("upstream timed out")
                || lower.contains("no live upstreams")
                || lower.contains("connect() failed")
                || lower.contains("connection refused")
                || lower.contains("connection reset by peer")
                || lower.contains("ssl handshake failure")
                || lower.contains("certificate verify failed")) {
            return LogSeverityLevel.ERROR_LEVEL;
        }
        if (lower.contains("exception")
                || lower.contains("caused by")
                || Pattern.compile("(?i)\\bfailed\\b").matcher(lower).find()
                || lower.contains("failure")
                || lower.contains("失败")
                || lower.contains("报错")
                || Pattern.compile("错误\\s*[:：]").matcher(lower).find()) {
            return LogSeverityLevel.ERROR_LEVEL;
        }
        if (Pattern.compile("(?i)\\bwarn\\b").matcher(lower).find()
                || lower.contains("警告")
                || Pattern.compile("(?i)\\btimeout\\b").matcher(lower).find()
                || lower.contains("超时")) {
            return LogSeverityLevel.WARNING_LEVEL;
        }
        if (Pattern.compile("(?i)\\binfo\\b").matcher(lower).find()
                || lower.contains("信息")
                || Pattern.compile("(?i)\\bstarted\\b").matcher(lower).find()
                || lower.contains("启动")) {
            return LogSeverityLevel.INFO_LEVEL;
        }
        if (Pattern.compile("(?i)\\bdebug\\b").matcher(lower).find() || lower.contains("调试")) {
            return LogSeverityLevel.DEBUG_LEVEL;
        }
        return LogSeverityLevel.UNKNOWN_LEVEL;
    }
}
