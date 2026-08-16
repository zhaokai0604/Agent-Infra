package com.award.log.controller;

import com.award.log.common.Result;
import com.award.log.config.OpsDryRunProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/platform")
@RequiredArgsConstructor
public class BackendProbeController {

    private final JdbcTemplate jdbcTemplate;
    private final OpsDryRunProperties opsDryRunProperties;

    @GetMapping("/backend-probe")
    public Result<Map<String, Object>> backendProbe() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("globalDryRun", opsDryRunProperties.isGlobalDryRun());
        body.put("logAlarmColumns", columnsOf("log_alarm"));
        body.put("alarmRuleColumns", columnsOf("alarm_rule"));
        body.put("logAlarmCount", scalarCount("log_alarm"));
        body.put("alarmRuleCount", scalarCount("alarm_rule"));
        return Result.success(body);
    }

    private List<String> columnsOf(String tableName) {
        return jdbcTemplate.query(
                "SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? ORDER BY ORDINAL_POSITION",
                (rs, rowNum) -> rs.getString(1),
                tableName);
    }

    private Long scalarCount(String tableName) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + tableName, Long.class);
    }
}
