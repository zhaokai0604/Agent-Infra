package com.award.log.service;

import com.award.log.mapper.LogAnalysisDetailMapper;
import com.award.log.model.LogAnalysisDetail;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Elasticsearch 未启用或查询失败时，从 MariaDB 日志分析明细表回退读取。
 */
@Slf4j
@Service
public class LogQueryFallbackService {

    private final LogAnalysisDetailMapper detailMapper;

    public LogQueryFallbackService(LogAnalysisDetailMapper detailMapper) {
        this.detailMapper = detailMapper;
    }

    public List<LogAnalysisDetail> queryRecent(
            int days,
            String severity,
            String keyword,
            Boolean anomalyOnly,
            int limit) {
        int windowDays = Math.max(1, Math.min(days, 30));
        int cap = Math.max(1, Math.min(limit, 500));
        try {
            return detailMapper.selectRecentDetails(windowDays, severity, keyword, anomalyOnly, cap);
        } catch (Exception e) {
            log.warn("MariaDB 日志回退查询失败: {}", e.getMessage());
            return List.of();
        }
    }
}
