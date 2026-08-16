package com.award.log.service;

import com.award.log.agent.DrainTemplateNoveltyTracker;
import com.award.log.handler.PerformanceWebSocketHandler;
import com.award.log.model.patrol.PatrolCorrelationSnapshot;
import com.award.log.model.patrol.PatrolFinding;
import com.award.log.security.OpsPathPolicy;
import com.award.log.security.signal.SecuritySignalService;
import com.award.log.util.OsRuntime;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Patrol service with hotspot detection and auto-remediation handoff.
 */
@Slf4j
@Service
public class OpsPatrolService {

    private static final int MAX_ALERTS = 120;

    private final StatisticsService statisticsService;
    private final AiLogAlarmService aiLogAlarmService;
    private final DrainTemplateNoveltyTracker noveltyTracker;
    private final OpsPathPolicy opsPathPolicy;
    private final SecuritySignalService securitySignalService;

    @Autowired(required = false)
    private OpsAutoRemediationService opsAutoRemediationService;

    @Autowired(required = false)
    private PerformanceWebSocketHandler performanceWebSocketHandler;

    @Autowired(required = false)
    private PatrolHistoryService patrolHistoryService;

    @Value("${ops.patrol.disk-warn-percent:80}")
    private double diskWarnPercent;

    @Value("${ops.patrol.cpu-warn-percent:85}")
    private double cpuWarnPercent;

    @Value("${ops.patrol.anomaly-spike-factor:2.0}")
    private double anomalySpikeFactor;

    @Value("${ops.patrol.inspect-roots:/var/log,/tmp}")
    private String inspectRootsRaw;

    @Value("${ops.patrol.dedup-window-ms:900000}")
    private long dedupWindowMs;

    @Value("${ops.patrol.disk-hotspot-top-n:5}")
    private int diskHotspotTopN;

    @Value("${ops.patrol.error-alarm-min:3}")
    private int errorAlarmMin;

    @Value("${ops.patrol.disk-hotspot-max-depth:3}")
    private int diskHotspotMaxDepth;

    @Value("${ops.patrol.disk-hotspot-visit-budget:12000}")
    private int diskHotspotVisitBudget;

    @Value("${ops.patrol.disk-hotspot-child-limit:120}")
    private int diskHotspotChildLimit = 120;

    @Value("${ops.patrol.disk-hotspot-cache-ms:120000}")
    private long diskHotspotCacheMs = 120_000L;

    @Value("${ops.patrol.disk-hotspot-command-timeout-ms:8000}")
    private long diskHotspotCommandTimeoutMs = 8_000L;

    @Value("${ops.patrol.anomaly-min-delta:5}")
    private int anomalyMinDelta;

    @Value("${ops.patrol.anomaly-baseline-ema-alpha:0.35}")
    private double anomalyBaselineEmaAlpha;

    private final ConcurrentLinkedDeque<Map<String, Object>> recentAlerts = new ConcurrentLinkedDeque<>();
    private final ConcurrentHashMap<String, Long> dedupSuppressUntil = new ConcurrentHashMap<>();

    private volatile List<Map<String, Object>> lastFindings = List.of();
    private volatile long lastAnomalyTotal = -1L;
    private volatile boolean anomalyBaselineInitialized = false;
    private volatile Map<String, Object> lastCorrelationSnapshot = Map.of();
    private volatile String diskHotspotCacheKey = "";
    private volatile long diskHotspotCacheAtMs = 0L;
    private volatile List<Map<String, Object>> diskHotspotCache = List.of();
    private final Object diskHotspotCacheLock = new Object();

    public OpsPatrolService(
            StatisticsService statisticsService,
            AiLogAlarmService aiLogAlarmService,
            DrainTemplateNoveltyTracker noveltyTracker,
            OpsPathPolicy opsPathPolicy,
            SecuritySignalService securitySignalService) {
        this.statisticsService = statisticsService;
        this.aiLogAlarmService = aiLogAlarmService;
        this.noveltyTracker = noveltyTracker;
        this.opsPathPolicy = opsPathPolicy;
        this.securitySignalService = securitySignalService;
    }

    public void applyHotConfig(List<String> inspectRoots,
                               double diskWarnPercent,
                               double cpuWarnPercent,
                               double anomalySpikeFactor,
                               int errorAlarmMin) {
        if (inspectRoots != null && !inspectRoots.isEmpty()) {
            this.inspectRootsRaw = String.join(",", inspectRoots);
        }
        this.diskWarnPercent = diskWarnPercent;
        this.cpuWarnPercent = cpuWarnPercent;
        this.anomalySpikeFactor = anomalySpikeFactor;
        this.errorAlarmMin = errorAlarmMin;
    }

    public List<Map<String, Object>> getRecentAlerts(int limit) {
        List<Map<String, Object>> out = new ArrayList<>();
        int i = 0;
        for (Map<String, Object> alert : recentAlerts) {
            out.add(alert);
            if (++i >= limit) {
                break;
            }
        }
        return out;
    }

    public List<Map<String, Object>> getLastFindingsSnapshot() {
        return lastFindings;
    }

    public Map<String, Object> getLastCorrelationSnapshot() {
        return lastCorrelationSnapshot;
    }

    public void runPatrolCycle() {
        long nowMs = System.currentTimeMillis();
        List<Map<String, Object>> findings = new ArrayList<>();
        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

        Map<String, Object> perf = safePerf();
        double disk = asDouble(perf.get("diskUsage"));
        double cpu = asDouble(perf.get("cpuUsage"));
        double mem = asDouble(perf.get("memoryUsage"));

        Map<String, Object> correlation = new LinkedHashMap<>();
        correlation.put("timestamp", ts);
        correlation.put("diskUsagePct", disk);
        correlation.put("cpuUsagePct", cpu);
        correlation.put("memoryUsagePct", mem);

        long errorAlarms = 0;
        long fatalAlarms = 0;
        long totalAlarms = 0;
        try {
            Map<String, Object> alarmStats = aiLogAlarmService.getAlarmStatistics(1, null, null);
            if (alarmStats != null) {
                totalAlarms = asLong(alarmStats.get("totalAlarms"));
                Object dist = alarmStats.get("levelDistribution");
                if (dist instanceof List<?> list) {
                    for (Object row : list) {
                        if (!(row instanceof Map<?, ?> map)) {
                            continue;
                        }
                        Object levelValue = map.containsKey("level") ? map.get("level") : "";
                        String level = String.valueOf(levelValue).toUpperCase(Locale.ROOT);
                        long count = asLong(map.get("count"));
                        if (level.contains("ERROR")) {
                            errorAlarms += count;
                        }
                        if (level.contains("FATAL")) {
                            fatalAlarms += count;
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.debug("patrol alarm statistics unavailable: {}", e.getMessage());
        }
        correlation.put("alarmTotal24h", totalAlarms);
        correlation.put("alarmErrorApprox", errorAlarms);
        correlation.put("alarmFatalApprox", fatalAlarms);

        long anomalyTotal = -1L;
        try {
            Map<String, Object> anomaly = statisticsService.getAnomalyLogStatistics(1);
            if (anomaly != null && anomaly.get("totalAnomalyLogs") instanceof Number n) {
                anomalyTotal = n.longValue();
            }
        } catch (Exception e) {
            log.debug("patrol anomaly statistics unavailable: {}", e.getMessage());
        }
        correlation.put("anomalyLogsDay1", anomalyTotal);

        int zombies = countZombieProcesses();
        correlation.put("zombieProcesses", zombies);

        long novelKinds = noveltyTracker.countFirstSeenInWindow(3_600_000L);
        correlation.put("novelDrainTemplateKinds1h", novelKinds);

        List<Map<String, Object>> diskHotspots = scanDiskHotspots();
        correlation.put("diskHotspotsTop", diskHotspots);

        Map<String, Object> securitySummary = securitySignalService.summary();
        correlation.put("securitySignals", securitySummary);

        lastCorrelationSnapshot = new LinkedHashMap<>(correlation);

        maybeAddDeduped(finding(
                "WARN",
                "disk",
                "disk usage high",
                String.format(Locale.ROOT,
                        "current disk usage is about %.1f%%, check large files and log growth",
                        disk)),
                findings, nowMs, disk >= diskWarnPercent);

        maybeAddDeduped(finding(
                "WARN",
                "cpu",
                "cpu usage high",
                String.format(Locale.ROOT,
                        "current cpu usage is about %.1f%%, check hot processes",
                        cpu)),
                findings, nowMs, cpu >= cpuWarnPercent);

        maybeAddDeduped(finding(
                "WARN",
                "memory",
                "memory usage high",
                String.format(Locale.ROOT,
                        "current memory usage is about %.1f%%, watch for swapping or OOM pressure",
                        mem)),
                findings, nowMs, mem >= 90);

        if (errorAlarms + fatalAlarms >= errorAlarmMin) {
            maybeAddDeduped(finding(
                    "WARN",
                    "alarm_severity",
                    "many ERROR/FATAL alarms",
                    String.format(Locale.ROOT,
                            "recent window ERROR=%d, FATAL=%d, total alarms=%d",
                            errorAlarms, fatalAlarms, totalAlarms)),
                    findings, nowMs, true);
        }

        applyAnomalyBaseline(anomalyTotal, correlation, findings, nowMs);

        if (zombies > 0) {
            maybeAddDeduped(finding(
                    "WARN",
                    "process",
                    "zombie process detected",
                    String.format(Locale.ROOT,
                            "about %d zombie processes detected, inspect unreaped parent processes",
                            zombies)),
                    findings, nowMs, true);
        }

        if (novelKinds >= 3) {
            maybeAddDeduped(finding(
                    "INFO",
                    "drain",
                    "new log templates appeared recently",
                    String.format(Locale.ROOT,
                            "about %d newly seen Drain templates appeared in the last hour",
                            novelKinds)),
                    findings, nowMs, true);
        }

        if (!diskHotspots.isEmpty()) {
            Map<String, Object> top = diskHotspots.get(0);
            maybeAddDeduped(finding(
                    "INFO",
                    "disk_hotspot",
                    "large directory hotspot",
                    String.format(Locale.ROOT,
                            "example hotspot %s is about %.1f MiB",
                            top.get("path"),
                            asDouble(top.get("approxMiB")))),
                    findings, nowMs, asDouble(top.get("approxMiB")) >= 256);
        }

        if (Boolean.TRUE.equals(securitySummary.get("hasThreat"))) {
            maybeAddDeduped(finding(
                    "HIGH",
                    "security_signal",
                    "IDS/PIDS security alert",
                    securitySignalService.buildThreatSummaryText(securitySummary)),
                    findings, nowMs, true);
        }

        lastFindings = List.copyOf(findings);

        for (Map<String, Object> finding : findings) {
            Map<String, Object> alert = new LinkedHashMap<>(finding);
            alert.put("timestamp", ts);
            alert.put("patrolRunTs", ts);
            recentAlerts.addFirst(alert);
            trimAlerts();
        }

        if (!findings.isEmpty() && performanceWebSocketHandler != null) {
            Map<String, Object> envelope = new HashMap<>();
            envelope.put("channel", "patrol_alert");
            envelope.put("timestamp", ts);
            envelope.put("findings", findings);
            envelope.put("correlation", correlation);
            try {
                performanceWebSocketHandler.broadcastJson(envelope);
            } catch (Exception e) {
                log.warn("patrol WebSocket push failed: {}", e.getMessage());
            }
        }

        if (!findings.isEmpty()) {
            log.info("[ops patrol] {} effective findings generated after dedup", findings.size());
        }

        if (opsAutoRemediationService != null) {
            try {
                opsAutoRemediationService.afterPatrol(lastCorrelationSnapshot, List.copyOf(findings));
            } catch (Exception e) {
                log.warn("[ops patrol] auto-remediation stage failed: {}", e.getMessage());
            }
        }

        if (patrolHistoryService != null) {
            List<PatrolFinding> typedFindings = findings.stream()
                    .map(PatrolFinding::fromMap)
                    .filter(java.util.Objects::nonNull)
                    .toList();
            patrolHistoryService.saveRun(
                    PatrolCorrelationSnapshot.fromMap(lastCorrelationSnapshot),
                    typedFindings);
        }
    }

    private void maybeAddDeduped(Map<String, Object> finding,
                                 List<Map<String, Object>> findings,
                                 long nowMs,
                                 boolean condition) {
        if (!condition) {
            return;
        }
        String key = String.valueOf(finding.get("level")) + "|"
                + finding.get("code") + "|"
                + finding.get("title");
        long until = dedupSuppressUntil.getOrDefault(key, 0L);
        if (until > nowMs) {
            return;
        }
        dedupSuppressUntil.put(key, nowMs + dedupWindowMs);
        findings.add(finding);
    }

    private void trimAlerts() {
        while (recentAlerts.size() > MAX_ALERTS) {
            recentAlerts.removeLast();
        }
    }

    private Map<String, Object> safePerf() {
        try {
            Map<String, Object> perf = statisticsService.getSystemPerformance(null);
            return perf != null ? perf : Map.of();
        } catch (Exception e) {
            return Map.of();
        }
    }

    private List<Map<String, Object>> scanDiskHotspots() {
        long now = System.currentTimeMillis();
        int depth = Math.min(Math.max(diskHotspotMaxDepth, 1), 5);
        int visitBudget = Math.min(Math.max(diskHotspotVisitBudget, 200), 50_000);
        int childLimit = Math.min(Math.max(diskHotspotChildLimit, 20), 500);
        String cacheKey = inspectRootsRaw + "|" + depth + "|" + visitBudget + "|" + childLimit + "|" + diskHotspotTopN;
        List<Map<String, Object>> cached = cachedDiskHotspots(cacheKey, now);
        if (cached != null) {
            return cached;
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        for (String raw : inspectRootsRaw.split(",")) {
            String root = raw.trim();
            if (root.isEmpty()) {
                continue;
            }
            String normalized = root.replace("\\", "/");
            if (!opsPathPolicy.isAllowedDiskInsightRoot(normalized)) {
                continue;
            }
            Path path = Paths.get(OsRuntime.isWindows() ? root : normalized);
            try {
                if (!Files.isDirectory(path)) {
                    continue;
                }
                if (OsRuntime.isUnixLike()) {
                    rows.addAll(scanDiskHotspotsViaDu(path, depth, visitBudget, childLimit));
                } else {
                    rows.addAll(scanDiskHotspotsViaWalk(path, depth, visitBudget, childLimit));
                }
            } catch (Exception e) {
                log.debug("skip disk hotspot root {}: {}", root, e.getMessage());
            }
        }
        List<Map<String, Object>> result = rows.stream()
                .sorted(Comparator.comparingDouble(item -> -asDouble(item.get("approxMiB"))))
                .limit(Math.max(1, diskHotspotTopN))
                .collect(Collectors.toList());
        storeDiskHotspotCache(cacheKey, now, result);
        return copyHotspotRows(result);
    }

    private List<Map<String, Object>> scanDiskHotspotsViaDu(Path root, int depth, int visitBudget, int childLimit) {
        List<Map<String, Object>> rows = new ArrayList<>();
        String rootArg = root.toString().replace('\\', '/');
        try {
            Process process = new ProcessBuilder("du", "-xk", "--max-depth=" + depth, rootArg)
                    .redirectErrorStream(true)
                    .start();
            CompletableFuture<String> outputFuture = CompletableFuture.supplyAsync(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    return reader.lines().collect(Collectors.joining("\n"));
                } catch (Exception e) {
                    return "";
                }
            });
            if (!process.waitFor(Math.max(1000L, diskHotspotCommandTimeoutMs), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                outputFuture.cancel(true);
                return scanDiskHotspotsViaWalk(root, depth, visitBudget, childLimit);
            }
            if (process.exitValue() != 0) {
                return scanDiskHotspotsViaWalk(root, depth, visitBudget, childLimit);
            }
            String output = outputFuture.get(2, TimeUnit.SECONDS);
            for (String line : output.split("\n")) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                int tabIndex = trimmed.indexOf('\t');
                if (tabIndex <= 0) {
                    continue;
                }
                long kb;
                try {
                    kb = Long.parseLong(trimmed.substring(0, tabIndex).trim());
                } catch (NumberFormatException e) {
                    continue;
                }
                String path = trimmed.substring(tabIndex + 1).trim();
                if (path.isEmpty() || kb <= 0 || path.equals(rootArg)) {
                    continue;
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("path", path);
                row.put("approxMiB", kb / 1024.0);
                row.put("scanMode", "du");
                rows.add(row);
            }
        } catch (Exception e) {
            return scanDiskHotspotsViaWalk(root, depth, visitBudget, childLimit);
        }
        return rows;
    }

    private List<Map<String, Object>> scanDiskHotspotsViaWalk(Path root, int depth, int visitBudget, int childLimit) {
        List<Map<String, Object>> rows = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(root)) {
            int scanned = 0;
            AtomicInteger visits = new AtomicInteger();
            for (Path child : stream) {
                if (++scanned > childLimit) {
                    break;
                }
                if (visits.get() >= visitBudget) {
                    break;
                }
                int before = visits.get();
                long bytes = recursiveDirectorySizeBytes(child, depth, visits, visitBudget);
                if (bytes <= 0) {
                    continue;
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("path", child.toString());
                row.put("approxMiB", bytes / (1024.0 * 1024.0));
                row.put("scanMode", "walk");
                if (visits.get() >= visitBudget || visits.get() == before) {
                    row.put("truncated", true);
                }
                rows.add(row);
            }
        } catch (Exception e) {
            log.debug("walk hotspot scan failed {}: {}", root, e.getMessage());
        }
        return rows;
    }

    private long recursiveDirectorySizeBytes(Path path, int remainingDepth, AtomicInteger visitBudget, int maxVisits) {
        if (visitBudget.get() >= maxVisits) {
            return 0L;
        }
        visitBudget.incrementAndGet();
        try {
            if (Files.isRegularFile(path)) {
                return Files.size(path);
            }
            if (!Files.isDirectory(path) || remainingDepth < 1) {
                return 0L;
            }
            long sum = 0L;
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(path)) {
                for (Path entry : stream) {
                    if (visitBudget.get() >= maxVisits) {
                        break;
                    }
                    if (Files.isRegularFile(entry)) {
                        visitBudget.incrementAndGet();
                        sum += Files.size(entry);
                    } else if (Files.isDirectory(entry)) {
                        sum += recursiveDirectorySizeBytes(entry, remainingDepth - 1, visitBudget, maxVisits);
                    }
                }
            }
            return sum;
        } catch (Exception e) {
            return 0L;
        }
    }

    private List<Map<String, Object>> cachedDiskHotspots(String key, long now) {
        if (diskHotspotCacheMs <= 0) {
            return null;
        }
        if (!Objects.equals(key, diskHotspotCacheKey)) {
            return null;
        }
        if (now - diskHotspotCacheAtMs > diskHotspotCacheMs) {
            return null;
        }
        return copyHotspotRows(diskHotspotCache);
    }

    private void storeDiskHotspotCache(String key, long now, List<Map<String, Object>> rows) {
        if (diskHotspotCacheMs <= 0) {
            return;
        }
        synchronized (diskHotspotCacheLock) {
            diskHotspotCacheKey = key;
            diskHotspotCacheAtMs = now;
            diskHotspotCache = copyHotspotRows(rows);
        }
    }

    private static List<Map<String, Object>> copyHotspotRows(List<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> copy = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            copy.add(new LinkedHashMap<>(row));
        }
        return copy;
    }

    private void applyAnomalyBaseline(long anomalyTotal,
                                      Map<String, Object> correlation,
                                      List<Map<String, Object>> findings,
                                      long nowMs) {
        if (anomalyTotal < 0) {
            return;
        }
        long previous = lastAnomalyTotal;
        if (!anomalyBaselineInitialized) {
            lastAnomalyTotal = anomalyTotal;
            anomalyBaselineInitialized = true;
            correlation.put("anomalyBaseline", anomalyTotal);
            correlation.put("anomalyBaselineAction", "seed");
            return;
        }

        boolean windowShrink = previous > 0 && anomalyTotal < previous * 0.55;
        boolean spike = !windowShrink
                && previous > 0
                && anomalyTotal >= previous * anomalySpikeFactor
                && anomalyTotal - previous >= anomalyMinDelta;

        if (spike) {
            correlation.put("anomalyBaseline", previous);
            correlation.put("anomalyBaselineAction", "hold_on_spike");
            correlation.put("anomalyObserved", anomalyTotal);
            maybeAddDeduped(finding(
                    "WARN",
                    "log_anomaly",
                    "abnormal logs significantly above baseline",
                    String.format(Locale.ROOT,
                            "last day anomaly count=%d, baseline=%d, delta=%.1f%%",
                            anomalyTotal,
                            previous,
                            previous > 0 ? (anomalyTotal - previous) * 100.0 / previous : 0)),
                    findings,
                    nowMs,
                    true);
            return;
        }

        if (windowShrink) {
            lastAnomalyTotal = anomalyTotal;
            correlation.put("anomalyBaseline", anomalyTotal);
            correlation.put("anomalyBaselineAction", "reset_window_shrink");
            return;
        }

        double alpha = Math.min(1.0, Math.max(0.05, anomalyBaselineEmaAlpha));
        long updated = Math.round(previous * (1.0 - alpha) + anomalyTotal * alpha);
        lastAnomalyTotal = updated;
        correlation.put("anomalyBaseline", updated);
        correlation.put("anomalyBaselineAction", "ema_update");
        correlation.put("anomalyObserved", anomalyTotal);
    }

    private static Map<String, Object> finding(String level, String code, String title, String detail) {
        Map<String, Object> finding = new LinkedHashMap<>();
        finding.put("level", level);
        finding.put("code", code);
        finding.put("title", title);
        finding.put("detail", detail);
        return finding;
    }

    private static double asDouble(Object value) {
        return value instanceof Number ? ((Number) value).doubleValue() : 0.0;
    }

    private static long asLong(Object value) {
        return value instanceof Number ? ((Number) value).longValue() : 0L;
    }

    private int countZombieProcesses() {
        if (!OsRuntime.isUnixLike()) {
            return 0;
        }
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "sh", "-c", "ps -eo stat= 2>/dev/null | grep -c '^Z' || true");
            pb.redirectErrorStream(true);
            Process process = pb.start();
            String line;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                line = reader.readLine();
            }
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return 0;
            }
            if (line != null && !line.isBlank()) {
                return Integer.parseInt(line.trim());
            }
        } catch (Exception e) {
            log.debug("skip zombie process count: {}", e.getMessage());
        }
        return 0;
    }
}
