package com.award.log.service;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 向工作台暴露「当前待处理运维事项」：巡检待确认方案 + 最近关联快照。
 */
@Service
@RequiredArgsConstructor
public class OpsOpenIncidentService {

    private final OpsPatrolService opsPatrolService;

    @Autowired(required = false)
    private OpsAutoRemediationService opsAutoRemediationService;

    public Map<String, Object> buildOpenIncidentContext() {
        Map<String, Object> incident = new LinkedHashMap<>();
        Map<String, Object> pending = opsAutoRemediationService != null
                ? opsAutoRemediationService.getPendingProposalView()
                : Map.of("hasPending", false);
        incident.put("pendingRemediation", pending);
        incident.put("correlation", opsPatrolService.getLastCorrelationSnapshot());
        incident.put("lastFindings", opsPatrolService.getLastFindingsSnapshot());
        boolean hasPending = Boolean.TRUE.equals(pending.get("hasPending"));
        incident.put("hasOpenIncident", hasPending);
        if (hasPending) {
            incident.put("suggestedUserMessage", "继续处理巡检待办");
            incident.put("assistantHint",
                    "存在巡检生成的待确认修复方案；用户可说「继续处理」由助手按方案执行（等价于确认执行）。");
        }
        return incident;
    }
}
