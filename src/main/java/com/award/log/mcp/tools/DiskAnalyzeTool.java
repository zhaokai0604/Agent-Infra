package com.award.log.mcp.tools;

import com.award.log.mcp.McpNestedResultSupport;
import com.award.log.mcp.McpToolResponses;
import com.award.log.security.OpsPathPolicy;
import com.award.log.util.OsRuntime;
import com.award.log.util.WindowsDriveSupport;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 磁盘综合分析（只读）：聚合全局使用率 + 可选热点目录扫描，用于「磁盘满 / I/O 异常」排查场景。
 * 热点扫描复用 {@link DiskTool} 的白名单策略。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DiskAnalyzeTool {

    private final DiskTool diskTool;
    private final ObjectMapper objectMapper;
    private final OpsPathPolicy opsPathPolicy;

    @Tool(name = "analyzeDiskPressure",
            description = "磁盘压力综合诊断：df/分区使用率汇总 + 可选热点目录 du 排序（白名单路径）。")
    public String analyzeDiskPressure(
            @ToolParam(description = "热点扫描根路径，默认 /var/log", required = false) String rootPath,
            @ToolParam(description = "是否执行热点目录扫描", required = false) Boolean includeHotspots,
            @ToolParam(description = "热点扫描返回条数，默认 12", required = false) Integer topN
    ) throws JsonProcessingException {
        long t0 = System.currentTimeMillis();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("overview", McpNestedResultSupport.unwrap(objectMapper, diskTool.checkDiskUsage()));
        boolean hot = Boolean.TRUE.equals(includeHotspots);
        if (hot) {
            if (OsRuntime.isWindows() && (rootPath == null || rootPath.isBlank())) {
                out.put("multiDrive", buildWindowsMultiDriveAnalysis(topN));
            } else {
                String root = (rootPath != null && !rootPath.isBlank()) ? rootPath.trim()
                        : "/var/log";
                int n = topN != null ? Math.min(Math.max(topN, 3), 40) : 12;
                out.put("hotspotsRank", McpNestedResultSupport.unwrap(objectMapper,
                        diskTool.rankDiskUsageUnderPath(root, 2, n)));
            }
        }
        out.put("hint", "结合 overview 各盘使用率与 Temp/热点目录定位垃圾文件；高占用盘优先清理 Temp 与白名单目录。");
        long duration = System.currentTimeMillis() - t0;
        out.put("durationMs", duration);
        String dataJson = objectMapper.writeValueAsString(out);
        return McpToolResponses.success(objectMapper, dataJson, duration);
    }

    private List<Map<String, Object>> buildWindowsMultiDriveAnalysis(Integer topN) throws JsonProcessingException {
        int n = topN != null ? Math.min(Math.max(topN, 3), 20) : 12;
        List<Map<String, Object>> drives = new ArrayList<>();
        for (String driveRoot : buildWindowsInsightRoots()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("drive", WindowsDriveSupport.driveLabel(driveRoot));
            row.put("root", driveRoot);
            try {
                Object hotspots = McpNestedResultSupport.unwrap(objectMapper,
                        diskTool.rankDiskUsageUnderPath(driveRoot, 2, Math.min(n, 8)));
                row.put("hotspots", hotspots);
            } catch (Exception e) {
                row.put("hotspotsError", e.getMessage());
            }
            drives.add(row);
        }
        return drives;
    }

    private List<String> buildWindowsInsightRoots() {
        LinkedHashSet<String> roots = new LinkedHashSet<>();
        addWindowsRoots(roots, opsPathPolicy.snapshotReadPrefixes());
        addWindowsRoots(roots, opsPathPolicy.snapshotTempCleanRoots());
        addWindowsRoots(roots, opsPathPolicy.snapshotLogCleanupRoots());
        return new ArrayList<>(roots);
    }

    private void addWindowsRoots(LinkedHashSet<String> roots, List<String> candidates) {
        if (candidates == null) {
            return;
        }
        for (String candidate : candidates) {
            String normalized = OpsPathPolicy.normalizeWindowsPath(candidate);
            if (!normalized.isEmpty() && opsPathPolicy.isAllowedDiskInsightRoot(normalized)) {
                roots.add(normalized);
            }
        }
    }
}
