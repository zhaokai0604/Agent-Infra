package com.award.log.analysis;

import com.award.log.model.LogProtocolType;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.MonthDay;
import java.time.OffsetDateTime;
import java.time.Year;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 单行日志的协议识别与时间抽取（面向 Windows 事件 / Linux syslog&journal / 应用日志混排），
 * 供批量分析与实时分析共用，避免 Windows 偏向实现导致 Linux 侧时间为空、时间轴与频次统计失真。
 */
public final class LogLineParseSupport {

    private static final ZoneId ZONE = ZoneId.systemDefault();

    /** 传统 syslog：Feb 10 14:22:01 */
    private static final Pattern SYSLOG_TRADITIONAL = Pattern.compile(
            "\\b(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)\\s+(\\d{1,2})\\s+(\\d{2}:\\d{2}:\\d{2})\\b",
            Pattern.CASE_INSENSITIVE);

    private static final DateTimeFormatter MONTH_DAY = DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH);

    private static final Pattern STD_SPACE = Pattern.compile("\\b(\\d{4}-\\d{2}-\\d{2})\\s+(\\d{2}:\\d{2}:\\d{2})\\b");

    /** Windows 风格 yyyy/M/d H:mm:ss */
    private static final Pattern WIN_YMD = Pattern.compile(
            "\\b(\\d{4})/(\\d{1,2})/(\\d{1,2})\\s+(\\d{1,2}):(\\d{2}):(\\d{2})\\b");

    /** Windows / US M/d/yyyy H:mm:ss */
    private static final Pattern WIN_MDY = Pattern.compile(
            "\\b(\\d{1,2})/(\\d{1,2})/(\\d{4})\\s+(\\d{1,2}):(\\d{2}):(\\d{2})\\b");

    /** Apache / Nginx common：10/Feb/2024:14:22:01 +0800 */
    private static final Pattern APACHE_TS = Pattern.compile(
            "\\[(\\d{2}/[A-Za-z]{3}/\\d{4}:\\d{2}:\\d{2}:\\d{2})\\s+[+-]\\d{4}\\]");

    private static final DateTimeFormatter APACHE_FMT = DateTimeFormatter.ofPattern("dd/MMM/yyyy:HH:mm:ss", Locale.ENGLISH);

    private static final Pattern SQL_VERB = Pattern.compile("\\b(select|insert|update|delete)\\b");

    private static final Pattern JOURNAL_SHORT = Pattern.compile(
            "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?(?:Z|[+-]\\d{2}:?\\d{2})?\\s+\\S+\\s+\\S+\\[\\d+\\]:");

    /** syslog/journal 行首 &lt;PRI&gt; */
    private static final Pattern SYSLOG_HAS_PRI = Pattern.compile("^<[0-9]{1,3}>");

    private LogLineParseSupport() {
    }

    public static LogProtocolType detectProtocol(String logLine) {
        if (logLine == null || logLine.isBlank()) {
            return LogProtocolType.UNKNOWN_LOG_TYPE;
        }
        String line = logLine;
        String lowerLog = line.toLowerCase(Locale.ROOT);

        if (line.matches("^(信息|警告|错误|关键|Information|Warning|Error|Critical)\\s+\\d{4}[/\\-]\\d{1,2}[/\\-]\\d{1,2}.*")) {
            return LogProtocolType.WINDOWS_EVENT_LOG;
        }

        if (line.trim().startsWith("{") && line.contains("__REALTIME_TIMESTAMP")) {
            return LogProtocolType.LINUX_SYSTEM_LOG;
        }
        if (LinuxLogParseSupport.isCriKubernetesLine(line)) {
            return LogProtocolType.LINUX_SYSTEM_LOG;
        }
        if (LinuxLogParseSupport.isDockerJsonFileLine(line)) {
            return LogProtocolType.LINUX_SYSTEM_LOG;
        }
        if (SYSLOG_HAS_PRI.matcher(line.trim()).find()) {
            return LogProtocolType.LINUX_SYSTEM_LOG;
        }
        if (SYSLOG_TRADITIONAL.matcher(line).find()
                || JOURNAL_SHORT.matcher(line).find()
                || line.matches("(?i)^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}[^\\s]*\\s+\\S+\\s+(sshd|systemd|kernel)\\b.*")) {
            return LogProtocolType.LINUX_SYSTEM_LOG;
        }

        if (lowerLog.contains("kernel")
                || lowerLog.contains("sshd")
                || lowerLog.contains("systemd")
                || lowerLog.contains("sudo:")
                || lowerLog.contains("crond")
                || lowerLog.contains("rsyslogd")
                || lowerLog.contains("auditd")
                || lowerLog.matches(".*\\d{4}-\\d{2}-\\d{2}\\s+\\d{2}:\\d{2}:\\d{2}.*")) {
            return LogProtocolType.LINUX_SYSTEM_LOG;
        }
        if (LinuxLogParseSupport.containsLinuxServiceHint(line)) {
            return LogProtocolType.LINUX_SYSTEM_LOG;
        }

        if (lowerLog.contains("<event") || lowerLog.contains("microsoft-windows") || lowerLog.contains("事件id")) {
            return LogProtocolType.WINDOWS_EVENT_LOG;
        }
        if (lowerLog.contains("traceid") || lowerLog.contains("exception") || lowerLog.contains("controller") || lowerLog.contains("requestid")) {
            return LogProtocolType.APPLICATION_LOG;
        }
        if (ServerLogParseSupport.looksLikeWebMiddlewareServer(line)) {
            return LogProtocolType.APPLICATION_LOG;
        }
        if (ServerLogParseSupport.looksLikeDbOrCacheServerDaemon(line)) {
            return LogProtocolType.DATABASE_LOG;
        }
        if (lowerLog.contains("sql") || lowerLog.contains("mysql") || lowerLog.contains("oracle") || SQL_VERB.matcher(lowerLog).find()) {
            return LogProtocolType.DATABASE_LOG;
        }
        if (lowerLog.contains("ip:") || lowerLog.contains("port:") || lowerLog.contains("tcp") || lowerLog.contains("udp") || lowerLog.contains("packet")) {
            return LogProtocolType.NETWORK_DEVICE_LOG;
        }
        return LogProtocolType.UNIVERSAL_TEXT_LOG;
    }

    /**
     * 抽取并规范为 {@code yyyy-MM-dd HH:mm:ss}（系统默认时区）；无法识别则返回空串。
     */
    public static String extractNormalizedTimeString(String logLine) {
        LocalDateTime ldt = extractLocalDateTime(logLine);
        return ldt == null ? "" : ldt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    public static long toEpochMillis(String normalizedYyyyMmDdHhMmSs) {
        if (normalizedYyyyMmDdHhMmSs == null || normalizedYyyyMmDdHhMmSs.isBlank()) {
            return 0L;
        }
        try {
            LocalDateTime ldt = LocalDateTime.parse(
                    normalizedYyyyMmDdHhMmSs, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            return ldt.atZone(ZONE).toInstant().toEpochMilli();
        } catch (DateTimeParseException e) {
            return 0L;
        }
    }

    private static LocalDateTime extractLocalDateTime(String line) {
        if (line == null || line.isBlank()) {
            return null;
        }
        LocalDateTime ldt = LinuxLogParseSupport.tryJournalJsonRealtime(line);
        if (ldt != null) {
            return ldt;
        }
        ldt = LinuxLogParseSupport.tryRfc5424Timestamp(line);
        if (ldt != null) {
            return ldt;
        }
        ldt = LinuxLogParseSupport.tryAuditMsgTimestamp(line);
        if (ldt != null) {
            return ldt;
        }
        ldt = LinuxLogParseSupport.tryCriKubernetesTimestamp(line);
        if (ldt != null) {
            return ldt;
        }
        ldt = LinuxLogParseSupport.tryContainerDockerJsonTimestamp(line);
        if (ldt != null) {
            return ldt;
        }
        ldt = ServerLogParseSupport.tryAll(line);
        if (ldt != null) {
            return ldt;
        }
        ldt = tryCommonParsers(line);
        if (ldt != null) {
            return ldt;
        }
        String stripped = LinuxLogParseSupport.stripLeadingSyslogPri(line);
        if (!stripped.equals(line)) {
            return tryCommonParsers(stripped);
        }
        return null;
    }

    private static LocalDateTime tryCommonParsers(String line) {
        LocalDateTime ldt = tryIsoFlexible(line);
        if (ldt != null) {
            return ldt;
        }
        ldt = tryApache(line);
        if (ldt != null) {
            return ldt;
        }
        ldt = tryStdSpace(line);
        if (ldt != null) {
            return ldt;
        }
        ldt = tryWinYmd(line);
        if (ldt != null) {
            return ldt;
        }
        ldt = tryWinMdy(line);
        if (ldt != null) {
            return ldt;
        }
        return trySyslogTraditional(line);
    }

    private static LocalDateTime tryIsoFlexible(String line) {
        Matcher m = Pattern.compile(
                        "\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}:\\d{2}(?:\\.\\d{1,9})?(?:Z|[+-]\\d{2}:?\\d{2})?")
                .matcher(line);
        if (!m.find()) {
            return null;
        }
        String raw = m.group().trim().replace(' ', 'T');
        try {
            return OffsetDateTime.parse(raw, DateTimeFormatter.ISO_DATE_TIME).atZoneSameInstant(ZONE).toLocalDateTime();
        } catch (DateTimeParseException ignored) {
            // fall through
        }
        try {
            return LocalDateTime.parse(raw, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static LocalDateTime tryApache(String line) {
        Matcher m = APACHE_TS.matcher(line);
        if (!m.find()) {
            return null;
        }
        try {
            return LocalDateTime.parse(m.group(1), APACHE_FMT);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static LocalDateTime tryStdSpace(String line) {
        Matcher m = STD_SPACE.matcher(line);
        if (!m.find()) {
            return null;
        }
        try {
            return LocalDateTime.parse(m.group(1) + "T" + m.group(2), DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static LocalDateTime tryWinYmd(String line) {
        Matcher m = WIN_YMD.matcher(line);
        if (!m.find()) {
            return null;
        }
        try {
            LocalDate d = LocalDate.of(
                    Integer.parseInt(m.group(1)),
                    Integer.parseInt(m.group(2)),
                    Integer.parseInt(m.group(3)));
            LocalTime t = LocalTime.of(
                    Integer.parseInt(m.group(4)),
                    Integer.parseInt(m.group(5)),
                    Integer.parseInt(m.group(6)));
            return LocalDateTime.of(d, t);
        } catch (Exception e) {
            return null;
        }
    }

    private static LocalDateTime tryWinMdy(String line) {
        Matcher m = WIN_MDY.matcher(line);
        if (!m.find()) {
            return null;
        }
        try {
            LocalDate d = LocalDate.of(
                    Integer.parseInt(m.group(3)),
                    Integer.parseInt(m.group(1)),
                    Integer.parseInt(m.group(2)));
            LocalTime t = LocalTime.of(
                    Integer.parseInt(m.group(4)),
                    Integer.parseInt(m.group(5)),
                    Integer.parseInt(m.group(6)));
            return LocalDateTime.of(d, t);
        } catch (Exception e) {
            return null;
        }
    }

    private static LocalDateTime trySyslogTraditional(String line) {
        Matcher m = SYSLOG_TRADITIONAL.matcher(line);
        if (!m.find()) {
            return null;
        }
        try {
            MonthDay md = MonthDay.parse(m.group(1) + " " + Integer.parseInt(m.group(2)), MONTH_DAY);
            LocalTime time = LocalTime.parse(m.group(3), DateTimeFormatter.ofPattern("HH:mm:ss"));
            int year = Year.now(ZONE).getValue();
            LocalDate date = md.atYear(year);
            LocalDateTime ldt = LocalDateTime.of(date, time);
            if (ldt.atZone(ZONE).isAfter(ZonedDateTime.now(ZONE).plusHours(30))) {
                ldt = LocalDateTime.of(md.atYear(year - 1), time);
            }
            return ldt;
        } catch (Exception e) {
            return null;
        }
    }
}
