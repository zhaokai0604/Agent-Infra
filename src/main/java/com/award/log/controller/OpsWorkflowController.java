package com.award.log.controller;

import com.award.log.agent.awm.FailureInsight;
import com.award.log.agent.awm.FailureInsightService;
import com.award.log.agent.awm.LlmWorkflowInductor;
import com.award.log.agent.awm.OpsWorkflow;
import com.award.log.agent.awm.WorkflowInductionService;
import com.award.log.agent.awm.WorkflowMemoryService;
import com.award.log.common.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * AWM（Agent Workflow Memory）只读/管理接口，便于答辩展示已沉淀套路。
 */
@RestController
@RequestMapping("/api/ops/workflow")
@RequiredArgsConstructor
public class OpsWorkflowController {

    private final WorkflowMemoryService workflowMemoryService;
    private final WorkflowInductionService workflowInductionService;
    private final FailureInsightService failureInsightService;
    private final LlmWorkflowInductor llmWorkflowInductor;

    @GetMapping("/memory")
    public Result<Map<String, Object>> memory(
            @RequestParam(required = false) String domain,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "20") int limit
    ) {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("enabled", workflowMemoryService.isEnabled());
        resp.put("storedCount", workflowMemoryService.countEnabled());
        resp.put("totalRuns", workflowMemoryService.countAllRuns());
        resp.put("supportedToolCount", com.award.log.agent.awm.AwmToolProfile.supportedTools().size());
        resp.put("llmInductionAvailable", llmWorkflowInductor.isAvailable());

        List<OpsWorkflow> workflows;
        if (domain == null || domain.isBlank() || "all".equalsIgnoreCase(domain.trim())) {
            workflows = workflowMemoryService.listEnabled();
        } else {
            // 管理页按域全量列表；Top-K retrieve 仅用于对话注入，避免面板最多只显示 5 条
            workflows = workflowMemoryService.listByDomain(domain.trim());
        }
        if (q != null && !q.isBlank()) {
            String needle = q.trim().toLowerCase(Locale.ROOT);
            workflows = workflows.stream()
                    .filter(w -> {
                        String blob = ((w.title() == null ? "" : w.title()) + " "
                                + (w.description() == null ? "" : w.description()) + " "
                                + (w.workflowId() == null ? "" : w.workflowId())).toLowerCase(Locale.ROOT);
                        return blob.contains(needle);
                    })
                    .toList();
        }
        int cap = Math.min(50, Math.max(1, limit));
        if (workflows.size() > cap) {
            workflows = workflows.subList(0, cap);
        }
        resp.put("domain", domain == null || domain.isBlank() ? "all" : domain.trim());
        resp.put("workflows", workflows.stream().map(this::enrichWorkflow).toList());
        return Result.success(resp);
    }

    @GetMapping("/runs")
    public Result<Map<String, Object>> runs(
            @RequestParam String workflowId,
            @RequestParam(defaultValue = "8") int limit
    ) {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("workflowId", workflowId);
        resp.put("runCount", workflowMemoryService.countRuns(workflowId));
        resp.put("successCount", workflowMemoryService.getSuccessCount(workflowId));
        resp.put("runs", workflowMemoryService.listRecentRuns(workflowId, limit));
        return Result.success(resp);
    }

    private Map<String, Object> enrichWorkflow(OpsWorkflow wf) {
        Map<String, Object> m = new LinkedHashMap<>(wf.toContextMap());
        if (wf.workflowId() != null) {
            m.put("successCount", workflowMemoryService.getSuccessCount(wf.workflowId()));
            m.put("runCount", workflowMemoryService.countRuns(wf.workflowId()));
        }
        return m;
    }

    @GetMapping("/failure-insights")
    public Result<Map<String, Object>> failureInsights(
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "12") int limit
    ) {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("enabled", failureInsightService.isEnabled());
        int cap = Math.min(50, Math.max(1, limit));
        if (q != null && !q.isBlank()) {
            resp.putAll(failureInsightService.buildContextMap(q));
        } else {
            resp.put("storedCount", failureInsightService.countStored());
            resp.put("lessons", failureInsightService.listRecent(cap).stream()
                    .map(FailureInsight::toContextMap)
                    .toList());
            resp.put("note", "以下为历史安全拦截教训，仅供参考；不得绕过 OpsTrustPolicy 与安全门");
        }
        return Result.success(resp);
    }

    /**
     * 工作台前端本地拦截时可上报，避免「前端拦了、后端从不落库」导致安全教训永远为空。
     */
    @PostMapping("/failure-insights/capture")
    public Result<Map<String, Object>> captureFailureInsight(@RequestBody Map<String, Object> body) {
        String userInput = str(body.get("userInput"));
        String securityCode = str(body.get("securityCode"));
        String toolName = str(body.get("toolName"));
        String detail = str(body.get("detail"));
        if (detail == null || detail.isBlank()) {
            detail = "workbench client capture";
        }
        boolean captured = failureInsightService.captureFromClient(userInput, securityCode, toolName, detail);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("captured", captured);
        resp.put("enabled", failureInsightService.isEnabled());
        resp.put("storedCount", failureInsightService.countStored());
        return Result.success(resp);
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o).trim();
    }

    @PostMapping("/induce-from-audit")
    public Result<Map<String, Object>> induceFromAudit(@RequestParam(defaultValue = "20") int limit) {
        WorkflowInductionService.InductionResult result =
                workflowInductionService.induceFromRecentTraces(Math.min(100, Math.max(1, limit)));
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("created", result.created());
        resp.put("scanned", result.scanned());
        resp.put("skippedNotEligible", result.skippedNotEligible());
        resp.put("skippedDuplicate", result.skippedDuplicate());
        resp.put("skippedShortTools", result.skippedShortTools());
        resp.put("skippedDomainFull", result.skippedDomainFull());
        resp.put("upsertFailed", result.upsertFailed());
        resp.put("awmEnabled", result.awmEnabled());
        resp.put("hint", result.hintZh());
        resp.put("storedCount", workflowMemoryService.countEnabled());
        return Result.success(resp);
    }
}
