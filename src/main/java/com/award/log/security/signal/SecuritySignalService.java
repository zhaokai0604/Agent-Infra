package com.award.log.security.signal;

import com.award.log.util.TimeSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.stream.Collectors;

@Service
public class SecuritySignalService {

    private final SecuritySignalNormalizer normalizer;
    private final TimeSource timeSource;
    private final ConcurrentLinkedDeque<SecuritySignal> recentSignals = new ConcurrentLinkedDeque<>();

    @Value("${security.signal.recent-max:256}")
    private int recentMax;

    @Value("${security.signal.active-window-ms:3600000}")
    private long activeWindowMs;

    public SecuritySignalService(SecuritySignalNormalizer normalizer, TimeSource timeSource) {
        this.normalizer = normalizer;
        this.timeSource = timeSource;
    }

    public SecuritySignal ingest(String sourceHint, Object payload) {
        SecuritySignal signal = normalizer.normalize(sourceHint, payload, timeSource.currentTimeMillis());
        recentSignals.addFirst(signal);
        trimToRecentMax();
        return signal;
    }

    public List<SecuritySignal> ingestBatch(String sourceHint, List<?> payloads) {
        if (payloads == null || payloads.isEmpty()) {
            return List.of();
        }
        List<SecuritySignal> out = new ArrayList<>(payloads.size());
        for (Object payload : payloads) {
            out.add(ingest(sourceHint, payload));
        }
        return out;
    }

    public List<SecuritySignal> recent(int limit) {
        if (limit <= 0) {
            return List.of();
        }
        List<SecuritySignal> out = new ArrayList<>(limit);
        int count = 0;
        for (SecuritySignal signal : recentSignals) {
            out.add(signal);
            if (++count >= limit) {
                break;
            }
        }
        return out;
    }

    public List<Map<String, Object>> recentAsMaps(int limit) {
        return recent(limit).stream().map(SecuritySignal::toMap).toList();
    }

    public List<SecuritySignal> recentHighPriority(int limit, long windowMs) {
        long cutoff = timeSource.currentTimeMillis() - Math.max(1L, windowMs);
        return recentSignals.stream()
                .filter(Objects::nonNull)
                .filter(signal -> signal.effectiveTimeMs() >= cutoff)
                .filter(SecuritySignal::isHighOrAbove)
                .sorted(Comparator.comparingLong(SecuritySignal::effectiveTimeMs).reversed())
                .limit(Math.max(0, limit))
                .toList();
    }

    public List<Map<String, Object>> recentHighPriorityAsMaps(int limit, long windowMs) {
        return recentHighPriority(limit, windowMs).stream().map(SecuritySignal::toMap).toList();
    }

    public boolean hasThreatSignals(long windowMs) {
        Map<String, Object> summary = summary(windowMs);
        return Boolean.TRUE.equals(summary.get("hasThreat"));
    }

    public Map<String, Object> summary() {
        return summary(activeWindowMs);
    }

    public Map<String, Object> summary(long windowMs) {
        long cutoff = timeSource.currentTimeMillis() - Math.max(1L, windowMs);
        List<SecuritySignal> active = recentSignals.stream()
                .filter(Objects::nonNull)
                .filter(signal -> signal.effectiveTimeMs() >= cutoff)
                .sorted(Comparator.comparingLong(SecuritySignal::effectiveTimeMs).reversed())
                .toList();

        long criticalCount = active.stream().filter(SecuritySignal::isCritical).count();
        long highOrAboveCount = active.stream().filter(SecuritySignal::isHighOrAbove).count();
        long blockedCount = active.stream().filter(SecuritySignal::blocked).count();

        Map<String, Long> sourceTypes = active.stream().collect(Collectors.groupingBy(
                SecuritySignal::sourceType,
                LinkedHashMap::new,
                Collectors.counting()));
        Map<String, Long> severities = active.stream().collect(Collectors.groupingBy(
                SecuritySignal::severity,
                LinkedHashMap::new,
                Collectors.counting()));
        List<String> topTitles = active.stream()
                .map(SecuritySignal::title)
                .filter(title -> title != null && !title.isBlank())
                .distinct()
                .limit(5)
                .toList();
        List<String> hosts = active.stream()
                .map(SecuritySignal::host)
                .filter(host -> host != null && !host.isBlank())
                .distinct()
                .limit(5)
                .toList();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("windowMs", windowMs);
        out.put("totalCount", active.size());
        out.put("criticalCount", criticalCount);
        out.put("highOrAboveCount", highOrAboveCount);
        out.put("blockedCount", blockedCount);
        out.put("hasThreat", criticalCount > 0 || highOrAboveCount >= 2);
        out.put("sourceTypes", sourceTypes);
        out.put("severityCounts", severities);
        out.put("topTitles", topTitles);
        out.put("hosts", hosts);
        out.put("signals", active.stream().limit(10).map(SecuritySignal::toMap).toList());
        out.put("latest", active.isEmpty() ? Map.of() : active.get(0).toMap());
        return out;
    }

    public String buildThreatSummaryText(Map<String, Object> summary) {
        if (summary == null || summary.isEmpty()) {
            return "未收到有效安全信号";
        }
        long critical = asLong(summary.get("criticalCount"));
        long high = asLong(summary.get("highOrAboveCount"));
        long blocked = asLong(summary.get("blockedCount"));
        @SuppressWarnings("unchecked")
        List<String> titles = summary.get("topTitles") instanceof List<?> list
                ? list.stream().map(String::valueOf).limit(3).toList()
                : List.of();
        String titleSample = titles.isEmpty() ? "" : "，样例: " + String.join(" / ", titles);
        return String.format("近窗口高危/高优先级安全信号 %d 条，critical=%d，已阻断=%d%s",
                high, critical, blocked, titleSample);
    }

    private void trimToRecentMax() {
        int cap = Math.max(32, recentMax);
        while (recentSignals.size() > cap) {
            recentSignals.removeLast();
        }
    }

    private static long asLong(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }
}
