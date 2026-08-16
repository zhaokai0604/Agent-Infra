package com.award.log.controller;

import com.award.log.analyzer.LogCleaner;
import com.award.log.common.Result;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 日志清洗控制器
 * 用于处理日志清洗相关的请求
 */
@Slf4j
@Deprecated(since = "delivery-2026-07", forRemoval = false)
@Tag(name = "LogClean", description = "非默认交付面：无挂载 UI / 仅 API，见 docs/deployment/交付API白名单.md")
@RestController
@RequestMapping("/api/log/clean")
public class LogCleanController {

    @Resource
    private LogCleaner logCleaner;

    private final Map<String, LogCleaner.CleanRule> cleanRulesMap = new ConcurrentHashMap<>();

    @GetMapping("/rules")
    public Result<List<LogCleaner.CleanRule>> getCleanRules() {
        try {
            List<LogCleaner.CleanRule> rules = cleanRulesMap.values().stream().toList();
            return Result.success(rules);
        } catch (Exception e) {
            log.error("获取清洗规则失败: {}", e.getMessage(), e);
            return Result.error("获取清洗规则失败");
        }
    }

    @PostMapping("/rules")
    public Result<Void> saveCleanRules(@RequestBody List<LogCleaner.CleanRule> rules) {
        try {
            cleanRulesMap.clear();
            if (rules != null) {
                for (LogCleaner.CleanRule rule : rules) {
                    if (rule == null || rule.getId() == null || rule.getId().isBlank()) {
                        continue;
                    }
                    cleanRulesMap.put(rule.getId(), rule);
                }
            }
            log.info("保存清洗规则成功，共 {} 条规则", cleanRulesMap.size());
            return Result.success();
        } catch (Exception e) {
            log.error("保存清洗规则失败: {}", e.getMessage(), e);
            return Result.error("保存清洗规则失败");
        }
    }

    @PostMapping
    public Result<String> cleanLog(@RequestBody Map<String, Object> request) {
        try {
            if (request == null) {
                return Result.error("请求体不能为空");
            }
            String logContent = request.get("logContent") instanceof String s ? s : null;
            List<LogCleaner.CleanRule> rules = extractRules(request.get("rules"));

            if (logContent == null) {
                return Result.error("日志内容不能为空");
            }

            String cleanedContent = logCleaner.cleanLog(logContent, rules);
            return Result.success(cleanedContent);
        } catch (IllegalArgumentException e) {
            return Result.error(400, e.getMessage());
        } catch (Exception e) {
            log.error("清洗日志失败: {}", e.getMessage(), e);
            return Result.error("清洗日志失败");
        }
    }

    @SuppressWarnings("unchecked")
    private static List<LogCleaner.CleanRule> extractRules(Object raw) {
        if (raw == null) {
            return Collections.emptyList();
        }
        if (raw instanceof List<?> list) {
            if (list.isEmpty()) {
                return Collections.emptyList();
            }
            if (list.get(0) instanceof LogCleaner.CleanRule) {
                return (List<LogCleaner.CleanRule>) list;
            }
        }
        throw new IllegalArgumentException("rules 格式无效");
    }
}
