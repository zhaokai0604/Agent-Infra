package com.award.log.service.impl;

import com.award.log.mapper.LogAnalysisDetailMapper;
import com.award.log.mapper.LogAnalysisTaskMapper;
import com.award.log.service.StatisticsService;
import com.award.log.util.OsRuntime;
import lombok.extern.slf4j.Slf4j;
import oshi.SystemInfo;
import oshi.hardware.CentralProcessor;
import oshi.hardware.GlobalMemory;
import oshi.hardware.HWDiskStore;
import oshi.hardware.HardwareAbstractionLayer;
import oshi.hardware.NetworkIF;
import oshi.software.os.OperatingSystem;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.lang.management.ThreadMXBean;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 数据统计Service实现
 */
@Slf4j
@Service
public class StatisticsServiceImpl implements StatisticsService {

    @Autowired
    private LogAnalysisTaskMapper taskMapper;

    @Autowired
    private LogAnalysisDetailMapper detailMapper;

    @Autowired(required = false)
    private RedisTemplate<String, Object> redisTemplate;

    // 缓存键前缀
    private static final String CACHE_PREFIX = "statistics:";
    private static final String TASK_STATUS_KEY = CACHE_PREFIX + "task_status";
    private static final String LOG_SUMMARY_KEY_PREFIX = CACHE_PREFIX + "log_summary:v3:";

    // 缓存过期时间（10分钟）
    private static final long CACHE_EXPIRE_TIME = 10;

    /** Redis 未启用时的进程内短缓存 TTL（毫秒） */
    private static final long LOCAL_STATS_TTL_MS = 20_000;

    private volatile Map<String, Object> cachedPerformance;
    private volatile long cachedPerformanceAtMs;
    private volatile String cachedPerformanceKey = "";

    private final ConcurrentHashMap<Integer, CachedAnomalyStats> cachedAnomalyStats = new ConcurrentHashMap<>();

    private static final class CachedAnomalyStats {
        final Map<String, Object> data;
        final long cachedAtMs;

        CachedAnomalyStats(Map<String, Object> data, long cachedAtMs) {
            this.data = data;
            this.cachedAtMs = cachedAtMs;
        }
    }

    private static final class NetworkCounterState {
        final long bytesRecv;
        final long bytesSent;
        final long timestampMs;

        NetworkCounterState(long bytesRecv, long bytesSent, long timestampMs) {
            this.bytesRecv = bytesRecv;
            this.bytesSent = bytesSent;
            this.timestampMs = timestampMs;
        }
    }

    private static final ConcurrentHashMap<String, NetworkCounterState> networkCounterStates =
            new ConcurrentHashMap<>();

    /** OSHI 在龙架构/部分麒麟内核上常无法初始化 LinuxOperatingSystem，探测后缓存结果 */
    private static volatile Boolean oshiAvailable;

    private static boolean useOshiMetrics() {
        if (oshiAvailable != null) {
            return oshiAvailable;
        }
        if (OsRuntime.isLoongArch()) {
            oshiAvailable = false;
            return false;
        }
        try {
            new SystemInfo();
            oshiAvailable = true;
        } catch (Throwable t) {
            log.warn("OSHI 不可用，性能监控改用 MXBean/proc：{}", t.toString());
            oshiAvailable = false;
        }
        return oshiAvailable;
    }

    @Override
    public Map<String, Object> getRecentLogSummary(int days) {
        String cacheKey = LOG_SUMMARY_KEY_PREFIX + days;
        
        // 尝试从缓存获取
        if (redisTemplate != null) {
            try {
                Object cachedData = redisTemplate.opsForValue().get(cacheKey);
                if (cachedData != null && cachedData instanceof Map) {
                    log.info("从缓存获取近期日志汇总，天数: [{}]", days);
                    return (Map<String, Object>) cachedData;
                }
            } catch (Exception e) {
                log.warn("从缓存获取近期日志汇总失败: {}", e.getMessage());
            }
        }

        log.info("从数据库获取近期日志汇总，天数: [{}]", days);
        Map<String, Object> summary = new HashMap<>();

        int windowDays = Math.max(1, days);
        summary.put("summaryDays", windowDays);

        long totalTasks = taskMapper.countSinceDays(windowDays);
        summary.put("totalTasks", totalTasks);

        long totalLogs = detailMapper.countSinceDays(windowDays);
        summary.put("totalLogs", totalLogs);

        // 获取最近几天的任务趋势
        List<Map<String, Object>> taskTrend = taskMapper.getTaskTrend(days);
        summary.put("taskTrend", taskTrend);

        List<Map<String, Object>> anomalyDistribution = detailMapper.selectAnomalySeverityDistribution(days);
        summary.put("anomalyDistribution", anomalyDistribution);

        long anomalyLogs = 0L;
        if (anomalyDistribution != null) {
            for (Map<String, Object> row : anomalyDistribution) {
                Object c = row != null ? row.get("count") : null;
                if (c == null && row != null) {
                    c = row.get("COUNT");
                }
                if (c instanceof Number) {
                    anomalyLogs += ((Number) c).longValue();
                }
            }
        }
        summary.put("anomalyLogs", anomalyLogs);
        double anomalyRate = totalLogs > 0 ? (anomalyLogs * 100.0 / totalLogs) : 0.0;
        summary.put("anomalyRate", anomalyRate);

        // 存入缓存
        if (redisTemplate != null) {
            try {
                redisTemplate.opsForValue().set(cacheKey, summary, CACHE_EXPIRE_TIME, TimeUnit.MINUTES);
                log.info("近期日志汇总存入缓存，天数: [{}]", days);
            } catch (Exception e) {
                log.warn("近期日志汇总存入缓存失败: {}", e.getMessage());
            }
        }

        return summary;
    }

    // CPU使用率计算相关的静态变量
    private static long[] prevTicks = null;
    private static boolean initialized = false;

    @Override
    public Map<String, Object> getSystemPerformance(String preferredNetworkInterface) {
        String cacheKey = preferredNetworkInterface == null ? "" : preferredNetworkInterface.trim();
        long now = System.currentTimeMillis();
        Map<String, Object> localHit = cachedPerformance;
        if (localHit != null
                && cacheKey.equals(cachedPerformanceKey)
                && now - cachedPerformanceAtMs < LOCAL_STATS_TTL_MS) {
            return new HashMap<>(localHit);
        }

        log.debug("获取系统性能数据");
        Map<String, Object> performance;
        if (!useOshiMetrics()) {
            performance = collectPerformanceFallback(preferredNetworkInterface);
        } else {
            try {
                performance = collectPerformanceViaOshi(preferredNetworkInterface);
            } catch (Throwable t) {
                log.warn("OSHI 采集失败，降级 MXBean/proc：{}", t.toString());
                oshiAvailable = false;
                performance = collectPerformanceFallback(preferredNetworkInterface);
            }
        }

        cachedPerformance = performance;
        cachedPerformanceKey = cacheKey;
        cachedPerformanceAtMs = now;
        return new HashMap<>(performance);
    }

    private Map<String, Object> collectPerformanceViaOshi(String preferredNetworkInterface) {
        Map<String, Object> performance = new HashMap<>();

        // 使用oshi库获取真实的系统性能数据
        SystemInfo systemInfo = new SystemInfo();
            HardwareAbstractionLayer hal = systemInfo.getHardware();
            OperatingSystem os = systemInfo.getOperatingSystem();

            // 1. CPU使用率 (使用oshi库的方法，需要先获取基准值)
            CentralProcessor processor = hal.getProcessor();
            double cpuUsage = 0.0;
            try {
                // 获取CPU负载 ticks
                long[] ticks = processor.getSystemCpuLoadTicks();
                if (prevTicks != null) {
                    // 计算CPU使用率
                    double cpuLoad = processor.getSystemCpuLoadBetweenTicks(prevTicks);
                    cpuUsage = cpuLoad * 100.0;
                    cpuUsage = Math.max(0, Math.min(100, cpuUsage));
                } else if (!initialized) {
                    // 第一次调用时，尝试使用Java自带的OperatingSystemMXBean获取CPU使用率
                    OperatingSystemMXBean osMxBean = ManagementFactory.getOperatingSystemMXBean();
                    if (osMxBean instanceof com.sun.management.OperatingSystemMXBean) {
                        com.sun.management.OperatingSystemMXBean sunOsMxBean = (com.sun.management.OperatingSystemMXBean) osMxBean;
                        double cpuLoad = sunOsMxBean.getSystemCpuLoad();
                        if (cpuLoad >= 0) {
                            cpuUsage = cpuLoad * 100.0;
                            cpuUsage = Math.max(0, Math.min(100, cpuUsage));
                        }
                    }
                    initialized = true;
                }
                // 更新基准值
                prevTicks = ticks;
            } catch (Exception e) {
                log.warn("获取CPU使用率失败: {}", e.getMessage());
                // 尝试使用Java自带的OperatingSystemMXBean获取CPU使用率
                OperatingSystemMXBean osMxBean = ManagementFactory.getOperatingSystemMXBean();
                if (osMxBean instanceof com.sun.management.OperatingSystemMXBean) {
                    com.sun.management.OperatingSystemMXBean sunOsMxBean = (com.sun.management.OperatingSystemMXBean) osMxBean;
                    double cpuLoad = sunOsMxBean.getSystemCpuLoad();
                    if (cpuLoad >= 0) {
                        cpuUsage = cpuLoad * 100.0;
                        cpuUsage = Math.max(0, Math.min(100, cpuUsage));
                    }
                }
            }
            performance.put("cpuUsage", cpuUsage);

            // 2. 内存使用率
            GlobalMemory memory = hal.getMemory();
            long totalMemory = memory.getTotal();
            long availableMemory = memory.getAvailable();
            long usedMemory = totalMemory - availableMemory;
            double memoryUsage = (double) usedMemory / totalMemory * 100.0;
            memoryUsage = Math.max(0, Math.min(100, memoryUsage));
            performance.put("memoryUsage", memoryUsage);
            performance.put("usedMemory", usedMemory);
            performance.put("totalMemory", totalMemory);
            performance.put("freeMemory", availableMemory);

            // 3. 磁盘使用率 (使用简单的方式计算)
            try {
                // 使用Java自带的方法获取磁盘使用情况
                FileStore fileStore = Files.getFileStore(Paths.get("."));
                long totalDiskSize = fileStore.getTotalSpace();
                long usableSpace = fileStore.getUsableSpace();
                long usedSpace = totalDiskSize - usableSpace;
                double diskUsage = (double) usedSpace / totalDiskSize * 100.0;
                diskUsage = Math.max(0, Math.min(100, diskUsage));
                
                performance.put("diskUsage", diskUsage);
                performance.put("diskPath", fileStore.toString());
                performance.put("diskTotal", totalDiskSize);
                performance.put("diskUsed", usedSpace);
                performance.put("diskFree", usableSpace);
            } catch (Exception e) {
                log.warn("获取磁盘使用情况失败: {}", e.getMessage());
                // 如果获取失败，使用默认值
                performance.put("diskUsage", 0.0);
                performance.put("diskPath", "");
            }

            // 4. 网络使用率 (计算单位时间内的网络传输速率与带宽的比值，更符合任务管理器的显示方式)
            List<NetworkIF> networkIFs = hal.getNetworkIFs();
            if (!networkIFs.isEmpty()) {
                // 刷新网卡统计快照，否则计数值可能长期不变
                for (NetworkIF nif : networkIFs) {
                    try {
                        nif.updateAttributes();
                    } catch (Exception ignored) {
                    }
                }

                List<String> interfaces = new ArrayList<>();
                networkIFs.forEach(nif -> interfaces.add(nif.getName()));
                performance.put("networkInterfaces", interfaces);

                // 优先按指定网卡匹配，否则取最活跃非环回网卡
                NetworkIF networkIF = null;
                if (preferredNetworkInterface != null && !preferredNetworkInterface.isBlank()) {
                    networkIF = networkIFs.stream()
                            .filter(nif -> preferredNetworkInterface.equalsIgnoreCase(nif.getName()))
                            .findFirst()
                            .orElse(null);
                }
                if (networkIF == null) {
                    networkIF = networkIFs.stream()
                            .filter(nif -> {
                                String name = (nif.getName() == null ? "" : nif.getName()).toLowerCase();
                                String display = (nif.getDisplayName() == null ? "" : nif.getDisplayName()).toLowerCase();
                                return !(name.contains("loopback") || name.equals("lo") || display.contains("loopback"));
                            })
                            .max(Comparator.comparingLong(nif -> nif.getBytesRecv() + nif.getBytesSent()))
                            .orElse(networkIFs.get(0));
                }
                long bytesRecv = networkIF.getBytesRecv();
                long bytesSent = networkIF.getBytesSent();
                long packetsRecv = networkIF.getPacketsRecv();
                long packetsSent = networkIF.getPacketsSent();
                long currentTimestamp = System.currentTimeMillis();
                double networkUsage = calculateNetworkUsage(
                        networkIF.getName(),
                        bytesRecv,
                        bytesSent,
                        currentTimestamp,
                        normalizeBandwidthBytesPerSecond(networkIF.getSpeed()));

                performance.put("networkUsage", networkUsage);
                performance.put("networkInterface", networkIF.getName());
                performance.put("bytesRecv", bytesRecv);
                performance.put("bytesSent", bytesSent);
                performance.put("packetsRecv", packetsRecv);
                performance.put("packetsSent", packetsSent);
            } else {
                // 如果没有找到网络接口，使用默认值
                performance.put("networkUsage", 0.0);
                performance.put("networkInterface", "eth0");
                performance.put("networkInterfaces", Collections.emptyList());
            }

            // 获取线程数
            int threadCount = Thread.activeCount();
            performance.put("threadCount", threadCount);

            // 获取当前时间
            performance.put("timestamp", System.currentTimeMillis());
            performance.put("metricsSource", "oshi");

        return performance;
    }

    /** 龙架构 / OSHI 不可用时的降级采集（MXBean + /proc + FileStore） */
    private Map<String, Object> collectPerformanceFallback(String preferredNetworkInterface) {
        Map<String, Object> performance = new HashMap<>();
        try {
            double cpuUsage = 0.0;
            OperatingSystemMXBean osMxBean = ManagementFactory.getOperatingSystemMXBean();
            if (osMxBean instanceof com.sun.management.OperatingSystemMXBean sunOsMxBean) {
                double cpuLoad = sunOsMxBean.getCpuLoad();
                if (cpuLoad < 0) {
                    cpuLoad = sunOsMxBean.getSystemCpuLoad();
                }
                if (cpuLoad >= 0) {
                    cpuUsage = Math.max(0, Math.min(100, cpuLoad * 100.0));
                }
            }
            performance.put("cpuUsage", cpuUsage);

            long totalMemory = 0L;
            long freeMemory = 0L;
            if (osMxBean instanceof com.sun.management.OperatingSystemMXBean sunMem) {
                totalMemory = sunMem.getTotalMemorySize();
                freeMemory = sunMem.getFreeMemorySize();
            }
            if (totalMemory <= 0) {
                long[] mem = readLinuxMemInfo();
                totalMemory = mem[0];
                freeMemory = mem[1];
            }
            long usedMemory = Math.max(0, totalMemory - freeMemory);
            double memoryUsage = totalMemory > 0 ? (usedMemory * 100.0 / totalMemory) : 0.0;
            performance.put("memoryUsage", Math.max(0, Math.min(100, memoryUsage)));
            performance.put("usedMemory", usedMemory);
            performance.put("totalMemory", totalMemory);
            performance.put("freeMemory", freeMemory);

            try {
                FileStore fileStore = Files.getFileStore(Paths.get("."));
                long totalDiskSize = fileStore.getTotalSpace();
                long usableSpace = fileStore.getUsableSpace();
                long usedSpace = totalDiskSize - usableSpace;
                double diskUsage = totalDiskSize > 0 ? (usedSpace * 100.0 / totalDiskSize) : 0.0;
                performance.put("diskUsage", Math.max(0, Math.min(100, diskUsage)));
                performance.put("diskPath", fileStore.toString());
                performance.put("diskTotal", totalDiskSize);
                performance.put("diskUsed", usedSpace);
                performance.put("diskFree", usableSpace);
            } catch (Exception e) {
                log.warn("获取磁盘使用情况失败: {}", e.getMessage());
                performance.put("diskUsage", 0.0);
                performance.put("diskPath", "");
            }

            applyNetworkFallbackMetrics(performance, preferredNetworkInterface);
            performance.put("threadCount", Thread.activeCount());
            performance.put("timestamp", System.currentTimeMillis());
            performance.put("metricsSource", "mxbean-proc");
        } catch (Exception e) {
            log.error("降级采集系统性能失败: {}", e.getMessage(), e);
            performance.put("cpuUsage", 0.0);
            performance.put("memoryUsage", 0.0);
            performance.put("diskUsage", 0.0);
            performance.put("networkUsage", 0.0);
            performance.put("networkInterface", "N/A");
            performance.put("networkInterfaces", Collections.emptyList());
            performance.put("threadCount", Thread.activeCount());
            performance.put("timestamp", System.currentTimeMillis());
            performance.put("error", "SYSTEM_METRICS_COLLECTION_FAILED");
            performance.put("metricsSource", "fallback-error");
        }
        return performance;
    }

    private static final Pattern PROC_NET_DEV = Pattern.compile("^\\s*(\\S+):\\s*(\\d+)\\s+\\d+\\s+\\d+\\s+\\d+\\s+\\d+\\s+\\d+\\s+\\d+\\s+\\d+\\s+(\\d+)");

    private void applyNetworkFallbackMetrics(Map<String, Object> performance, String preferredNetworkInterface) {
        Map<String, long[]> ifaceBytes = readProcNetDevBytes();
        performance.put("networkInterfaces", new ArrayList<>(ifaceBytes.keySet()));
        if (ifaceBytes.isEmpty()) {
            performance.put("networkUsage", 0.0);
            performance.put("networkInterface", "N/A");
            return;
        }
        String iface = preferredNetworkInterface;
        if (iface == null || iface.isBlank() || !ifaceBytes.containsKey(iface)) {
            iface = ifaceBytes.keySet().stream()
                    .filter(name -> !"lo".equals(name))
                    .findFirst()
                    .orElse(ifaceBytes.keySet().iterator().next());
        }
        long[] bytes = ifaceBytes.get(iface);
        long bytesRecv = bytes[0];
        long bytesSent = bytes[1];
        long currentTimestamp = System.currentTimeMillis();
        double networkUsage = calculateNetworkUsage(
                iface, bytesRecv, bytesSent, currentTimestamp, defaultBandwidthBytesPerSecond());
        performance.put("networkUsage", networkUsage);
        performance.put("networkInterface", iface);
        performance.put("bytesRecv", bytesRecv);
        performance.put("bytesSent", bytesSent);
    }

    private double calculateNetworkUsage(
            String interfaceKey,
            long bytesRecv,
            long bytesSent,
            long currentTimestamp,
            long bandwidthBytesPerSecond) {
        String key = interfaceKey == null || interfaceKey.isBlank() ? "unknown" : interfaceKey.trim();
        NetworkCounterState previous = networkCounterStates.put(
                key, new NetworkCounterState(bytesRecv, bytesSent, currentTimestamp));
        if (previous == null || previous.timestampMs <= 0 || currentTimestamp <= previous.timestampMs) {
            return 0.0;
        }
        long deltaBytesRecv = bytesRecv - previous.bytesRecv;
        long deltaBytesSent = bytesSent - previous.bytesSent;
        if (deltaBytesRecv < 0 || deltaBytesSent < 0) {
            return 0.0;
        }
        double timeDiff = (currentTimestamp - previous.timestampMs) / 1000.0;
        if (timeDiff <= 0) {
            return 0.0;
        }
        double bytesPerSecond = (deltaBytesRecv + deltaBytesSent) / timeDiff;
        long normalizedBandwidth = Math.max(1L, bandwidthBytesPerSecond);
        return Math.max(0, Math.min(100, (bytesPerSecond / normalizedBandwidth) * 100.0));
    }

    private long normalizeBandwidthBytesPerSecond(long linkSpeedBitsPerSecond) {
        if (linkSpeedBitsPerSecond <= 0) {
            return defaultBandwidthBytesPerSecond();
        }
        return Math.max(1L, linkSpeedBitsPerSecond / 8L);
    }

    private long defaultBandwidthBytesPerSecond() {
        return 100L * 1_000_000L / 8L;
    }

    private static long[] readLinuxMemInfo() {
        long total = 0L;
        long available = 0L;
        try (BufferedReader reader = Files.newBufferedReader(Paths.get("/proc/meminfo"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("MemTotal:")) {
                    total = parseProcMemKb(line) * 1024L;
                } else if (line.startsWith("MemAvailable:")) {
                    available = parseProcMemKb(line) * 1024L;
                }
            }
        } catch (Exception ignored) {
        }
        if (available <= 0 && total > 0) {
            available = total / 4;
        }
        return new long[]{total, available};
    }

    private static long parseProcMemKb(String line) {
        Matcher m = Pattern.compile("(\\d+)").matcher(line);
        return m.find() ? Long.parseLong(m.group(1)) : 0L;
    }

    private static Map<String, long[]> readProcNetDevBytes() {
        Map<String, long[]> out = new LinkedHashMap<>();
        try (BufferedReader reader = Files.newBufferedReader(Paths.get("/proc/net/dev"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                Matcher m = PROC_NET_DEV.matcher(line);
                if (m.find()) {
                    out.put(m.group(1), new long[]{Long.parseLong(m.group(2)), Long.parseLong(m.group(3))});
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }



    @Override
    public Map<String, Object> getTaskStatusStatistics() {
        // 尝试从缓存获取
        if (redisTemplate != null) {
            try {
                Object cachedData = redisTemplate.opsForValue().get(TASK_STATUS_KEY);
                if (cachedData != null && cachedData instanceof Map) {
                    log.info("从缓存获取任务状态统计");
                    return (Map<String, Object>) cachedData;
                }
            } catch (Exception e) {
                log.warn("从缓存获取任务状态统计失败: {}", e.getMessage());
            }
        }

        log.info("从数据库获取任务状态统计");
        Map<String, Object> statistics = new HashMap<>();

        // 获取各状态任务数量
        List<Map<String, Object>> statusCountList = taskMapper.getTaskStatusCount();
        // 将List转换为Map
        Map<String, Integer> statusCount = new HashMap<>();
        for (Map<String, Object> item : statusCountList) {
            if (item == null) {
                continue;
            }
            Object statusObj = item.get("status");
            if (statusObj == null) {
                statusObj = item.get("STATUS");
            }
            Object countObj = item.get("count");
            if (countObj == null) {
                countObj = item.get("COUNT");
            }
            if (statusObj == null || !(countObj instanceof Number)) {
                continue;
            }
            statusCount.put(String.valueOf(statusObj), ((Number) countObj).intValue());
        }
        statistics.put("statusCount", statusCount);

        // 获取任务完成情况统计
        List<Map<String, Object>> completionStatistics = taskMapper.getTaskCompletionStatistics();
        statistics.put("completionStatistics", completionStatistics);

        // 获取平均处理时间
        Double avgProcessingTime = taskMapper.getAverageProcessingTime();
        statistics.put("avgProcessingTime", avgProcessingTime);

        // 存入缓存
        if (redisTemplate != null) {
            try {
                redisTemplate.opsForValue().set(TASK_STATUS_KEY, statistics, CACHE_EXPIRE_TIME, TimeUnit.MINUTES);
                log.info("任务状态统计存入缓存");
            } catch (Exception e) {
                log.warn("任务状态统计存入缓存失败: {}", e.getMessage());
            }
        }

        return statistics;
    }



    @Override
    public Map<String, Object> getAnomalyLogStatistics(int days) {
        int windowDays = Math.max(1, days);
        long now = System.currentTimeMillis();
        CachedAnomalyStats hit = cachedAnomalyStats.get(windowDays);
        if (hit != null && now - hit.cachedAtMs < LOCAL_STATS_TTL_MS) {
            return new HashMap<>(hit.data);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("days", windowDays);
        List<Map<String, Object>> distribution = detailMapper.selectAnomalySeverityDistribution(windowDays);
        result.put("anomalyDistribution", distribution);
        long total = 0L;
        if (distribution != null) {
            for (Map<String, Object> row : distribution) {
                Object c = row.get("count");
                if (c instanceof Number) {
                    total += ((Number) c).longValue();
                }
            }
        }
        result.put("totalAnomalyLogs", total);
        cachedAnomalyStats.put(windowDays, new CachedAnomalyStats(result, now));
        return new HashMap<>(result);
    }
}
