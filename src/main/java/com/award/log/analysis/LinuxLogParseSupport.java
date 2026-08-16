package com.award.log.analysis;

import com.award.log.model.LogProtocolType;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Locale;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Linux 系统日志专项：syslog PRI / RFC5424、journal JSON 微秒时间戳、audit、tag[pid]、
 * systemd 单元引用、dmesg 相对时间标记等。
 */
public final class LinuxLogParseSupport {

    private static final ZoneId ZONE = ZoneId.systemDefault();

    private static final Pattern SYSLOG_PRI_LINE = Pattern.compile("^<([0-9]{1,3})>\\s*");

    /** RFC5424：&lt;PRI&gt;VERSION SP TIMESTAMP SP … */
    private static final Pattern RFC5424_HEADER = Pattern.compile(
            "^<[0-9]{1,3}>\\s*\\d+\\s+(\\S+)\\s+");

    /** journalctl -o json ：__REALTIME_TIMESTAMP 微秒 */
    private static final Pattern JOURNAL_REALTIME = Pattern.compile(
            "\"__REALTIME_TIMESTAMP\"\\s*:\\s*\"?([0-9]+)\"?");

    private static final Pattern AUDIT_MSG_TIME = Pattern.compile(
            "msg=audit\\(([0-9]+)\\.([0-9]+)\\s*:");

    private static final Pattern TAG_PID = Pattern.compile(
            "(?i)\\b([a-zA-Z][a-zA-Z0-9._-]*)\\[(\\d{1,8})]\\s*:");

    /** dmesg / kern：相对开机时间（仅元数据） */
    private static final Pattern KERNEL_BOOT = Pattern.compile("\\[\\s*([0-9]+\\.[0-9]+)]\\s*");

    private static final Pattern UNIT_REF = Pattern.compile(
            "(?i)\\b(?:UNIT|TriggeredBy|ExecStart)=([^\\s,]+)");

    /**
     * Kubernetes CRI 日志（containerd/docker）：行首为 RFC3339，紧随 stdout/stderr + P/F
     */
    private static final Pattern CRI_K8S_LINE_HEAD = Pattern.compile(
            "^(\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?Z)\\s+std(out|err)\\s+[PF]\\s+");

    /** 扩展：判定 Linux 系统域日志的附加关键词（与 {@link LogLineParseSupport} 协同） */
    private static final String[] LINUX_HINTS = {
            "kernel:", "[drm]", "[uart]", "ACPI", "BIOS", "systemd[1]:",
            "dbus-daemon", "NetworkManager", "firewalld", "polkitd", "auditd",
            "chronyd", "containerd", "dockerd", "kubelet", "crio", "podman",
            "udev", "ModemManager", "wpa_supplicant", "rsyslogd", "journal:",
            "pam_unix", "sudo:", "su:", "login:", "sshd", "crontab", "CRON",
            "(root) cmd", "Failed password", "Accepted password", "session opened",
            "Starting ", ".service:", ".socket:", ".target:", ".mount:", "dbus.service",
            "Out of memory", "oom-killer", "segfault at ", "Call Trace:", "XFS ", "EXT4-fs ",
            "apparmor=\"", "SELinux:", "type=AVC ", "type=SYSCALL ", "type=SERVICE ",
            "kernel audit", "libaudit",
            "namespace=", "pod/", "containerd", "kubelet", "coredns", "flannel", "calico",
            "istio", "ingress", "\"stream\":\"stderr\"", "\"stream\":\"stdout\""
    };

    private LinuxLogParseSupport() {
    }

    public static String stripLeadingSyslogPri(String line) {
        if (line == null) {
            return "";
        }
        Matcher m = SYSLOG_PRI_LINE.matcher(line);
        if (m.find() && m.start() == 0) {
            return line.substring(m.end());
        }
        return line;
    }

    public static Integer parseSyslogPriNumeric(String line) {
        if (line == null || line.isBlank()) {
            return null;
        }
        Matcher m = Pattern.compile("^<([0-9]{1,3})>").matcher(line.trim());
        if (!m.find()) {
            return null;
        }
        try {
            int v = Integer.parseInt(m.group(1));
            return (v >= 0 && v <= 191) ? v : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static int syslogFacilityCode(int pri) {
        return (pri & ~7) >> 3;
    }

    public static LocalDateTime tryJournalJsonRealtime(String line) {
        if (line == null || line.isBlank()) {
            return null;
        }
        String t = line.trim();
        if (!t.startsWith("{")) {
            return null;
        }
        Matcher m = JOURNAL_REALTIME.matcher(line);
        if (!m.find()) {
            return null;
        }
        try {
            long micros = Long.parseLong(m.group(1));
            if (micros <= 0) {
                return null;
            }
            long millis = micros / 1000L;
            return LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZONE);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static LocalDateTime tryRfc5424Timestamp(String line) {
        if (line == null || line.isBlank()) {
            return null;
        }
        Matcher m = RFC5424_HEADER.matcher(line.trim());
        if (!m.find()) {
            return null;
        }
        String ts = m.group(1);
        if ("-".equals(ts)) {
            return null;
        }
        return parseFlexibleIsoToLocal(ts);
    }

    /**
     * Docker / containerd json-file 落地格式：顶层 "time":"RFC3339"
     */
    public static LocalDateTime tryContainerDockerJsonTimestamp(String line) {
        if (line == null || line.length() < 30) {
            return null;
        }
        String s = line.trim();
        if (!s.startsWith("{")) {
            return null;
        }
        if (!s.contains("\"log\"") || !s.contains("\"stream\"")) {
            return null;
        }
        Matcher m = Pattern.compile("\"time\"\\s*:\\s*\"([^\"]+)\"").matcher(s);
        if (m.find()) {
            return parseFlexibleIsoToLocal(m.group(1));
        }
        m = Pattern.compile("\"timestamp\"\\s*:\\s*\"([^\"]+)\"").matcher(s);
        if (m.find()) {
            return parseFlexibleIsoToLocal(m.group(1));
        }
        return null;
    }

    /** K8s CRI：整行以标准输出时间开头 */
    public static LocalDateTime tryCriKubernetesTimestamp(String line) {
        if (line == null || line.isBlank()) {
            return null;
        }
        Matcher m = CRI_K8S_LINE_HEAD.matcher(line.trim());
        if (!m.find()) {
            return null;
        }
        return parseFlexibleIsoToLocal(m.group(1));
    }

    public static boolean isCriKubernetesLine(String line) {
        return line != null && CRI_K8S_LINE_HEAD.matcher(line.trim()).find();
    }

    public static boolean isDockerJsonFileLine(String line) {
        if (line == null || line.length() < 25) {
            return false;
        }
        String t = line.trim();
        return t.startsWith("{") && t.contains("\"log\"") && t.contains("\"stream\"");
    }

    /**
     * 容器/体系结构元数据（与 LINUX 协议正交，比赛演示：可看出解析器识别云原生格式）
     */
    public static void enrichContainerHints(Map<String, Object> featureMap, String line) {
        if (featureMap == null || line == null) {
            return;
        }
        if (isCriKubernetesLine(line)) {
            featureMap.put("containerFormat", "cri");
            Matcher m = CRI_K8S_LINE_HEAD.matcher(line.trim());
            if (m.find()) {
                featureMap.put("criStream", m.group(2));
            }
        } else if (isDockerJsonFileLine(line)) {
            featureMap.put("containerFormat", "docker-json-file");
        }
    }

    /** 报表/导出用简短摘要 */
    public static String formatParsedExtrasSummary(Map<String, Object> featureMap) {
        if (featureMap == null || featureMap.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        if (featureMap.containsKey("containerFormat")) {
            sb.append("容器:").append(featureMap.get("containerFormat"));
        }
        if (featureMap.containsKey("syslogPri")) {
            if (sb.length() > 0) {
                sb.append("; ");
            }
            sb.append("PRI:").append(featureMap.get("syslogPri"));
        }
        if (featureMap.containsKey("linuxTag")) {
            if (sb.length() > 0) {
                sb.append("; ");
            }
            sb.append("tag:").append(featureMap.get("linuxTag"));
        }
        if (featureMap.containsKey("systemdUnit")) {
            if (sb.length() > 0) {
                sb.append("; ");
            }
            sb.append("unit:").append(featureMap.get("systemdUnit"));
        }
        if (Boolean.TRUE.equals(featureMap.get("kernelRelativeTimeHint"))) {
            if (sb.length() > 0) {
                sb.append("; ");
            }
            sb.append("内核相对时间(无墙钟需看正文)");
        }
        return sb.toString();
    }

    public static LocalDateTime tryAuditMsgTimestamp(String line) {
        if (line == null || !line.contains("msg=audit(")) {
            return null;
        }
        Matcher m = AUDIT_MSG_TIME.matcher(line);
        if (!m.find()) {
            return null;
        }
        try {
            long sec = Long.parseLong(m.group(1));
            int fracLen = m.group(2).length();
            String frac = m.group(2);
            long nano;
            if (fracLen <= 9) {
                String padded = (frac + "000000000").substring(0, 9);
                nano = Long.parseLong(padded);
            } else {
                nano = Long.parseLong(frac.substring(0, 9));
            }
            return LocalDateTime.ofInstant(Instant.ofEpochSecond(sec, nano), ZONE);
        } catch (Exception e) {
            return null;
        }
    }

    private static LocalDateTime parseFlexibleIsoToLocal(String ts) {
        if (ts == null || ts.isBlank()) {
            return null;
        }
        String s = ts.trim();
        try {
            return java.time.OffsetDateTime.parse(s, DateTimeFormatter.ISO_DATE_TIME)
                    .atZoneSameInstant(ZONE).toLocalDateTime();
        } catch (DateTimeParseException ignored) {
        }
        try {
            return java.time.ZonedDateTime.parse(s).withZoneSameInstant(ZONE).toLocalDateTime();
        } catch (DateTimeParseException ignored) {
        }
        try {
            return LocalDateTime.parse(s, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** 从 syslog tag[pid]: 取 pid */
    public static String extractTagPid(String line) {
        if (line == null) {
            return null;
        }
        Matcher m = TAG_PID.matcher(line);
        return m.find() ? m.group(2) : null;
    }

    public static String extractTagName(String line) {
        if (line == null) {
            return null;
        }
        Matcher m = TAG_PID.matcher(line);
        return m.find() ? m.group(1) : null;
    }

    /**
     * dmesg 行若仅有相对开机时间，返回 true（无墙钟时间，需在 featureMap 标注）。
     */
    public static boolean looksLikeKernelBootRelative(String line) {
        if (line == null || line.length() < 8) {
            return false;
        }
        return KERNEL_BOOT.matcher(line).find() && (line.contains("kernel:")
                || line.toLowerCase(Locale.ROOT).contains("[drm]")
                || line.contains("Call Trace"));
    }

    /**
     * systemd / journal 文本行中的 UNIT=xxx
     */
    public static String extractSystemdUnit(String line) {
        if (line == null) {
            return null;
        }
        Matcher m = UNIT_REF.matcher(line);
        return m.find() ? m.group(1) : null;
    }

    public static boolean containsLinuxServiceHint(String line) {
        if (line == null || line.isEmpty()) {
            return false;
        }
        String lower = line.toLowerCase(Locale.ROOT);
        for (String h : LINUX_HINTS) {
            if (lower.contains(h.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 将 Linux 专用解析结果写入 featureMap（不改变既有字段语义，供报表/决策扩展）。
     */
    public static void enrichFeatureMap(Map<String, Object> featureMap, String rawLine, LogProtocolType protocol) {
        if (featureMap == null || rawLine == null || protocol != LogProtocolType.LINUX_SYSTEM_LOG) {
            return;
        }
        String line = rawLine;
        Integer pri = parseSyslogPriNumeric(line);
        if (pri != null) {
            featureMap.put("syslogPri", pri);
            featureMap.put("syslogFacility", syslogFacilityCode(pri));
        }
        String tagPid = extractTagPid(line);
        if (tagPid != null) {
            featureMap.put("linuxTagPid", tagPid);
        }
        String tagName = extractTagName(line);
        if (tagName != null) {
            featureMap.put("linuxTag", tagName);
        }
        String unit = extractSystemdUnit(line);
        if (unit != null) {
            featureMap.put("systemdUnit", unit);
        }
        if (looksLikeKernelBootRelative(line)) {
            featureMap.put("kernelRelativeTimeHint", true);
        }
        Matcher km = KERNEL_BOOT.matcher(line);
        if (km.find()) {
            featureMap.put("kernelBootOffsetSec", km.group(1));
        }
    }
}
