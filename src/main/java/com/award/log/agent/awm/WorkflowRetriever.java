package com.award.log.agent.awm;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 按 domain / finding / 用户话术检索 Top-K workflow（AWM M + W 的选择性注入）。
 */
@Component
@RequiredArgsConstructor
public class WorkflowRetriever {

    private final WorkflowMemoryService workflowMemoryService;

    public List<OpsWorkflow> retrieve(String domainTag, List<String> findingKinds, String userMessage, int limit) {
        if (!workflowMemoryService.isEnabled()) {
            return List.of();
        }
        int cap = Math.max(1, Math.min(limit, 5));
        List<OpsWorkflow> candidates = domainTag == null
                ? workflowMemoryService.listEnabled()
                : workflowMemoryService.listByDomain(domainTag);

        List<Scored> scored = new ArrayList<>();
        for (OpsWorkflow wf : candidates) {
            double score = score(wf, findingKinds, userMessage);
            if (score > 0) {
                scored.add(new Scored(wf, score));
            }
        }
        scored.sort(Comparator.comparingDouble(Scored::score).reversed());
        List<OpsWorkflow> out = new ArrayList<>();
        for (int i = 0; i < Math.min(cap, scored.size()); i++) {
            out.add(scored.get(i).workflow());
        }
        return out;
    }

    public OpsWorkflow bestMatch(String domainTag, List<String> findingKinds, String userMessage) {
        List<OpsWorkflow> list = retrieve(domainTag, findingKinds, userMessage, 1);
        return list.isEmpty() ? null : list.get(0);
    }

    public void recordHit(OpsWorkflow workflow) {
        if (workflow != null && workflow.workflowId() != null) {
            workflowMemoryService.recordUtility(workflow.workflowId());
        }
    }

    public List<OpsWorkflow> retrieveForContext(String domainTag, List<String> findingKinds, String userMessage) {
        return retrieve(domainTag, findingKinds, userMessage, 2);
    }

    private double score(OpsWorkflow wf, List<String> findingKinds, String userMessage) {
        double score = 1.0;
        if (findingKinds != null && wf.findingKinds() != null) {
            long overlap = findingKinds.stream()
                    .filter(k -> wf.findingKinds().stream().anyMatch(f -> f.equalsIgnoreCase(k)))
                    .count();
            score += overlap * 3.0;
        }
        if (userMessage != null && !userMessage.isBlank()) {
            String msg = userMessage.toLowerCase(Locale.ROOT);
            if (containsAny(msg, "磁盘", "空间", "disk", "c盘", "临时", "tmp", "temp", "日志", "清理", "clean")) {
                if ("disk".equals(wf.domainTag())) {
                    score += 2.0;
                }
            }
            if (containsAny(msg, "清理", "clean", "释放", "删掉临时", "清垃圾")) {
                if (wf.workflowId() != null && wf.workflowId().contains("remediation")) {
                    score += 1.5;
                }
            }
            if (containsAny(msg, "cpu", "负载", "进程", "卡顿", "占用过高", "性能", "慢")) {
                if ("cpu".equals(wf.domainTag())) {
                    score += 2.0;
                }
            }
            if (containsAny(msg, "服务", "systemd", "重启", "failed", "挂了", "unit")) {
                if ("service".equals(wf.domainTag())) {
                    score += 2.0;
                }
            }
            if (wf.title() != null) {
                String title = wf.title().toLowerCase(Locale.ROOT);
                for (String token : msg.split("[\\s,，。；;]+")) {
                    if (token.length() >= 2 && title.contains(token)) {
                        score += 0.4;
                    }
                }
            }
        }
        score += Math.min(5, wf.utilityCount()) * 0.2;
        return score;
    }

    private static boolean containsAny(String msg, String... keys) {
        for (String key : keys) {
            if (msg.contains(key)) {
                return true;
            }
        }
        return false;
    }

    private record Scored(OpsWorkflow workflow, double score) {
    }
}
