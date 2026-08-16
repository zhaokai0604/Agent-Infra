package com.award.log.agent.awm;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * Workflow Memory 持久化与内存缓存（AWM M + W）。
 */
@Slf4j
@Service
public class WorkflowMemoryService {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final CopyOnWriteArrayList<OpsWorkflow> cache = new CopyOnWriteArrayList<>();

    @Value("${agent.awm.enabled:true}")
    private boolean enabled;

    @Value("${agent.awm.seed-on-startup:true}")
    private boolean seedOnStartup;

    @Value("${agent.awm.max-workflows-per-domain:12}")
    private int maxWorkflowsPerDomain;

    public WorkflowMemoryService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public boolean isEnabled() {
        return enabled;
    }

    @PostConstruct
    void init() {
        ensureTable();
        reloadCache();
        if (seedOnStartup && enabled) {
            seedIfEmpty();
            seedMissingDomains();
        }
    }

    private void seedMissingDomains() {
        try {
            if (countByDomain("cpu") == 0) {
                for (OpsWorkflow wf : WorkflowSeedData.cpuWorkflows()) {
                    upsert(wf, false);
                }
                reloadCache();
                log.info("AWM 补种 CPU workflow {} 条", WorkflowSeedData.cpuWorkflows().size());
            }
            if (countByDomain("service") == 0) {
                for (OpsWorkflow wf : WorkflowSeedData.serviceWorkflows()) {
                    upsert(wf, false);
                }
                reloadCache();
                log.info("AWM 补种 service workflow {} 条", WorkflowSeedData.serviceWorkflows().size());
            }
        } catch (Exception e) {
            log.debug("AWM 补种跳过: {}", e.getMessage());
        }
    }

    void ensureTable() {
        try {
            jdbcTemplate.execute(
                    """
                    CREATE TABLE IF NOT EXISTS ops_workflow_memory (
                      id BIGINT NOT NULL AUTO_INCREMENT,
                      workflow_id VARCHAR(64) NOT NULL,
                      domain_tag VARCHAR(32) NOT NULL,
                      finding_kinds VARCHAR(256) DEFAULT NULL,
                      title VARCHAR(255) NOT NULL,
                      description TEXT,
                      steps_json LONGTEXT NOT NULL,
                      source_type VARCHAR(16) NOT NULL DEFAULT 'seed',
                      source_trace_id VARCHAR(64) DEFAULT NULL,
                      utility_count INT NOT NULL DEFAULT 0,
                      success_count INT NOT NULL DEFAULT 0,
                      enabled TINYINT(1) NOT NULL DEFAULT 1,
                      created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                      updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                      PRIMARY KEY (id),
                      UNIQUE KEY uk_workflow_id (workflow_id),
                      KEY idx_domain_tag (domain_tag),
                      KEY idx_enabled (enabled)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent Workflow Memory'
                    """);
            jdbcTemplate.execute(
                    """
                    CREATE TABLE IF NOT EXISTS ops_workflow_run (
                      id BIGINT NOT NULL AUTO_INCREMENT,
                      workflow_id VARCHAR(64) NOT NULL,
                      trace_id VARCHAR(64) DEFAULT NULL,
                      success TINYINT(1) NOT NULL DEFAULT 0,
                      steps_ok INT NOT NULL DEFAULT 0,
                      steps_total INT NOT NULL DEFAULT 0,
                      summary VARCHAR(512) DEFAULT NULL,
                      created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                      PRIMARY KEY (id),
                      KEY idx_workflow_run_wf (workflow_id),
                      KEY idx_workflow_run_trace (trace_id)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AWM workflow execution runs'
                    """);
            ensureSuccessCountColumn();
        } catch (Exception e) {
            log.warn("创建 AWM 表失败（可能无库权限）: {}", e.getMessage());
        }
    }

    private void ensureSuccessCountColumn() {
        try {
            jdbcTemplate.queryForObject(
                    "SELECT success_count FROM ops_workflow_memory LIMIT 1",
                    Integer.class);
        } catch (Exception e) {
            try {
                jdbcTemplate.execute(
                        "ALTER TABLE ops_workflow_memory ADD COLUMN success_count INT NOT NULL DEFAULT 0");
            } catch (Exception ignored) {
                log.debug("success_count 列迁移跳过: {}", ignored.getMessage());
            }
        }
    }

    public void reloadCache() {
        cache.clear();
        if (!enabled) {
            return;
        }
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT workflow_id, domain_tag, finding_kinds, title, description, steps_json, "
                            + "source_type, source_trace_id, utility_count, enabled "
                            + "FROM ops_workflow_memory WHERE enabled = 1 ORDER BY utility_count DESC, id ASC");
            for (Map<String, Object> row : rows) {
                OpsWorkflow wf = fromRow(row);
                if (wf != null) {
                    cache.add(wf);
                }
            }
            log.info("AWM 已加载 workflow {} 条", cache.size());
        } catch (Exception e) {
            log.warn("加载 workflow memory 失败: {}", e.getMessage());
        }
    }

    private void seedIfEmpty() {
        try {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM ops_workflow_memory", Integer.class);
            if (count != null && count > 0) {
                return;
            }
            for (OpsWorkflow wf : WorkflowSeedData.allSeeds()) {
                upsert(wf, false);
            }
            reloadCache();
            log.info("AWM seed 写入 workflow {} 条", WorkflowSeedData.allSeeds().size());
        } catch (Exception e) {
            log.warn("AWM seed 失败: {}", e.getMessage());
        }
    }

    public List<OpsWorkflow> listEnabled() {
        return Collections.unmodifiableList(new ArrayList<>(cache));
    }

    public List<OpsWorkflow> listByDomain(String domainTag) {
        if (domainTag == null) {
            return listEnabled();
        }
        String d = domainTag.toLowerCase(Locale.ROOT);
        return cache.stream()
                .filter(w -> d.equals(w.domainTag()))
                .collect(Collectors.toList());
    }

    /**
     * @return true 表示写入成功；失败返回 false（异常已记日志）
     */
    public boolean upsert(OpsWorkflow workflow, boolean incrementUtility) {
        if (workflow == null || workflow.workflowId() == null) {
            return false;
        }
        try {
            List<OpsWorkflowStep> steps = workflow.steps() != null ? workflow.steps() : List.of();
            String stepsJson = objectMapper.writeValueAsString(
                    steps.stream().map(OpsWorkflowStep::toMap).toList());
            String kinds = workflow.findingKinds() == null ? "" :
                    String.join(",", workflow.findingKinds());
            int utility = workflow.utilityCount() + (incrementUtility ? 1 : 0);

            jdbcTemplate.update(
                    """
                    INSERT INTO ops_workflow_memory
                      (workflow_id, domain_tag, finding_kinds, title, description, steps_json,
                       source_type, source_trace_id, utility_count, enabled)
                    VALUES (?,?,?,?,?,?,?,?,?,?)
                    ON DUPLICATE KEY UPDATE
                      domain_tag = VALUES(domain_tag),
                      finding_kinds = VALUES(finding_kinds),
                      title = VALUES(title),
                      description = VALUES(description),
                      steps_json = VALUES(steps_json),
                      source_type = IF(VALUES(source_type) = 'seed', source_type, VALUES(source_type)),
                      source_trace_id = COALESCE(VALUES(source_trace_id), source_trace_id),
                      utility_count = utility_count + ?,
                      enabled = VALUES(enabled)
                    """,
                    workflow.workflowId(),
                    workflow.domainTag(),
                    kinds,
                    workflow.title(),
                    workflow.description(),
                    stepsJson,
                    workflow.sourceType() != null ? workflow.sourceType() : "online",
                    workflow.sourceTraceId(),
                    utility,
                    workflow.enabled() ? 1 : 0,
                    incrementUtility ? 1 : 0
            );
            reloadCache();
            return true;
        } catch (Exception e) {
            log.warn("upsert workflow 失败 id={}: {}", workflow.workflowId(), e.getMessage());
            return false;
        }
    }

    public void recordUtility(String workflowId) {
        if (workflowId == null || workflowId.isBlank()) {
            return;
        }
        try {
            jdbcTemplate.update(
                    "UPDATE ops_workflow_memory SET utility_count = utility_count + 1 WHERE workflow_id = ?",
                    workflowId);
            reloadCache();
        } catch (Exception e) {
            log.debug("recordUtility 失败: {}", e.getMessage());
        }
    }

    public void recordSuccess(String workflowId) {
        if (workflowId == null || workflowId.isBlank()) {
            return;
        }
        try {
            jdbcTemplate.update(
                    "UPDATE ops_workflow_memory SET success_count = success_count + 1 WHERE workflow_id = ?",
                    workflowId);
            reloadCache();
        } catch (Exception e) {
            log.debug("recordSuccess 失败: {}", e.getMessage());
        }
    }

    public void recordRun(
            String workflowId,
            String traceId,
            boolean success,
            int stepsOk,
            int stepsTotal,
            String summary
    ) {
        if (workflowId == null || workflowId.isBlank()) {
            return;
        }
        try {
            jdbcTemplate.update(
                    """
                    INSERT INTO ops_workflow_run
                      (workflow_id, trace_id, success, steps_ok, steps_total, summary)
                    VALUES (?,?,?,?,?,?)
                    """,
                    workflowId,
                    traceId,
                    success ? 1 : 0,
                    stepsOk,
                    stepsTotal,
                    truncate(summary, 500));
        } catch (Exception e) {
            log.debug("recordRun 失败 workflowId={}: {}", workflowId, e.getMessage());
        }
    }

    public int countRuns(String workflowId) {
        if (workflowId == null || workflowId.isBlank()) {
            return 0;
        }
        try {
            Integer n = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM ops_workflow_run WHERE workflow_id = ?",
                    Integer.class,
                    workflowId);
            return n != null ? n : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    public int getSuccessCount(String workflowId) {
        if (workflowId == null || workflowId.isBlank()) {
            return 0;
        }
        try {
            Integer n = jdbcTemplate.queryForObject(
                    "SELECT success_count FROM ops_workflow_memory WHERE workflow_id = ?",
                    Integer.class,
                    workflowId);
            return n != null ? n : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    public int countAllRuns() {
        try {
            Integer n = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM ops_workflow_run",
                    Integer.class);
            return n != null ? n : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    public List<Map<String, Object>> listRecentRuns(String workflowId, int limit) {
        if (workflowId == null || workflowId.isBlank()) {
            return List.of();
        }
        int cap = Math.min(50, Math.max(1, limit));
        try {
            List<Map<String, Object>> raw = jdbcTemplate.queryForList(
                    """
                    SELECT id, workflow_id, trace_id, success, steps_ok, steps_total, summary, created_at
                    FROM ops_workflow_run
                    WHERE workflow_id = ?
                    ORDER BY id DESC
                    LIMIT ?
                    """,
                    workflowId,
                    cap);
            List<Map<String, Object>> out = new ArrayList<>();
            for (Map<String, Object> row : raw) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", row.get("id"));
                m.put("workflowId", row.get("workflow_id"));
                m.put("traceId", row.get("trace_id"));
                Object success = row.get("success");
                m.put("success", success instanceof Number n ? n.intValue() == 1 : Boolean.TRUE.equals(success));
                m.put("stepsOk", row.get("steps_ok"));
                m.put("stepsTotal", row.get("steps_total"));
                m.put("summary", row.get("summary"));
                m.put("createdAt", row.get("created_at"));
                out.add(m);
            }
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    public boolean existsWithToolSequence(String domainTag, List<String> toolSequence) {
        if (toolSequence == null || toolSequence.size() < 2) {
            return false;
        }
        return cache.stream()
                .filter(w -> domainTag == null || domainTag.equals(w.domainTag()))
                .anyMatch(w -> w.toolSequence().equals(toolSequence));
    }

    @SuppressWarnings("unchecked")
    OpsWorkflow fromRow(Map<String, Object> row) {
        try {
            List<Map<String, Object>> stepMaps = objectMapper.readValue(
                    String.valueOf(row.get("steps_json")), new TypeReference<>() {});
            List<OpsWorkflowStep> steps = new ArrayList<>();
            for (Map<String, Object> sm : stepMaps) {
                Map<String, String> args = new LinkedHashMap<>();
                Object rawArgs = sm.get("argsTemplate");
                if (rawArgs instanceof Map<?, ?> am) {
                    am.forEach((k, v) -> args.put(String.valueOf(k), v == null ? "" : String.valueOf(v)));
                }
                steps.add(new OpsWorkflowStep(
                        str(sm.get("envDesc")),
                        str(sm.get("reason")),
                        str(sm.get("toolName")),
                        args));
            }
            String kindsRaw = str(row.get("finding_kinds"));
            List<String> kinds = kindsRaw == null || kindsRaw.isBlank()
                    ? List.of()
                    : Arrays.stream(kindsRaw.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
            int utility = row.get("utility_count") instanceof Number n ? n.intValue() : 0;
            boolean en = row.get("enabled") instanceof Number n ? n.intValue() == 1 : true;
            return new OpsWorkflow(
                    str(row.get("workflow_id")),
                    str(row.get("domain_tag")),
                    kinds,
                    str(row.get("title")),
                    str(row.get("description")),
                    steps,
                    str(row.get("source_type")),
                    str(row.get("source_trace_id")),
                    utility,
                    en
            );
        } catch (Exception e) {
            log.debug("解析 workflow 行失败: {}", e.getMessage());
            return null;
        }
    }

    public int countEnabled() {
        return cache.size();
    }

    public int countByDomain(String domain) {
        return (int) cache.stream().filter(w -> domain.equals(w.domainTag())).count();
    }

    public boolean isDomainFull(String domainTag) {
        return countByDomain(domainTag) >= maxWorkflowsPerDomain;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return null;
        }
        return text.length() <= max ? text : text.substring(0, max) + "...";
    }
}
