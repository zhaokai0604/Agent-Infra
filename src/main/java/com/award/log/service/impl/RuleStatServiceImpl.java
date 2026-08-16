package com.award.log.service.impl;

import com.award.log.mapper.RuleHitStatMapper;
import com.award.log.model.RuleHitStat;
import com.award.log.service.RuleStatService;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class RuleStatServiceImpl implements RuleStatService {

    private final RuleHitStatMapper mapper;
    private final Map<String, AtomicLong> hit = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> miss = new ConcurrentHashMap<>();

    public RuleStatServiceImpl(RuleHitStatMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void record(String ruleId, String ruleName, boolean matched) {
        Map<String, AtomicLong> target = matched ? hit : miss;
        target.computeIfAbsent(ruleId + "|" + ruleName, k -> new AtomicLong(0)).incrementAndGet();
    }

    @Override
    public Map<String, Object> summary() {
        List<RuleHitStat> latest = mapper.selectLatest(50);
        long totalHit = latest.stream().mapToLong(v -> v.getHitCount() == null ? 0 : v.getHitCount()).sum();
        long totalMiss = latest.stream().mapToLong(v -> v.getMissCount() == null ? 0 : v.getMissCount()).sum();
        return Map.of("totalHit", totalHit, "totalMiss", totalMiss, "hitRate", totalHit / (double) Math.max(1, totalHit + totalMiss));
    }

    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 60000)
    public void flush() {
        LocalDateTime now = LocalDateTime.now();
        for (Map.Entry<String, AtomicLong> e : hit.entrySet()) {
            String[] key = e.getKey().split("\\|", 2);
            RuleHitStat row = new RuleHitStat();
            row.setRuleId(key[0]);
            row.setRuleName(key.length > 1 ? key[1] : key[0]);
            row.setHitCount(e.getValue().getAndSet(0));
            row.setMissCount(miss.getOrDefault(e.getKey(), new AtomicLong(0)).getAndSet(0));
            row.setWindowStart(now.minusMinutes(1));
            row.setWindowEnd(now);
            mapper.insert(row);
        }
    }
}
