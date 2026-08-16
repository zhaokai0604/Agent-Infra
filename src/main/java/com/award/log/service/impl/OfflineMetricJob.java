package com.award.log.service.impl;

import com.award.log.mapper.EngineOfflineMetricMapper;
import com.award.log.mapper.DecisionLogMapper;
import com.award.log.model.EngineOfflineMetric;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class OfflineMetricJob {

    private final EngineOfflineMetricMapper mapper;
    private final DecisionLogMapper decisionLogMapper;

    public OfflineMetricJob(EngineOfflineMetricMapper mapper,
                            DecisionLogMapper decisionLogMapper) {
        this.mapper = mapper;
        this.decisionLogMapper = decisionLogMapper;
    }

    @Scheduled(cron = "0 30 2 * * ?")
    public void calc() {
        LocalDateTime now = LocalDateTime.now();
        String start = now.minusDays(1).toString().replace("T", " ");
        String end = now.toString().replace("T", " ");
        List<Map<String, Object>> rows = decisionLogMapper.selectOfflinePairs(start, end);
        calcFor("RULE", rows);
        calcFor("RANDOM_FOREST", rows);
        calcFor("LLM", rows);
    }

    private void calcFor(String engine, List<Map<String, Object>> rows) {
        int sample = 0;
        int fp = 0;
        int fn = 0;
        for (Map<String, Object> row : rows) {
            if (!engine.equals(String.valueOf(row.get("engineType")))) {
                continue;
            }
            sample++;
            int predicted = ((Number) row.getOrDefault("predicted", 0)).intValue();
            int actual = ((Number) row.getOrDefault("actual", 0)).intValue();
            if (predicted == 1 && actual == 0) {
                fp++;
            } else if (predicted == 0 && actual == 1) {
                fn++;
            }
        }
        if (sample > 0) {
            save(engine, sample, fp, fn);
        }
    }

    private void save(String engine, int sample, int fp, int fn) {
        int tp = Math.max(0, sample - fp - fn);
        double precision = tp / (double) Math.max(1, tp + fp);
        double recall = tp / (double) Math.max(1, tp + fn);
        double f1 = (2D * precision * recall) / Math.max(1e-9, precision + recall);
        EngineOfflineMetric m = new EngineOfflineMetric();
        m.setEngineType(engine);
        m.setSampleSize(sample);
        m.setFalsePositive(fp);
        m.setFalseNegative(fn);
        m.setPrecisionScore(precision);
        m.setRecallScore(recall);
        m.setF1Score(f1);
        mapper.insert(m);
        log.info("离线指标已写入: engine={}, f1={}", engine, f1);
    }
}
