package com.award.log.service.impl;

import com.award.log.mapper.RuleHitStatMapper;
import com.award.log.model.RuleHitStat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RuleStatServiceImplTest {

    @Mock
    private RuleHitStatMapper mapper;

    private RuleStatServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new RuleStatServiceImpl(mapper);
    }

    @Test
    void recordShouldTrackHitsAndMisses() {
        service.record("r1", "rule-one", true);
        service.record("r1", "rule-one", false);
        service.flush();
        verify(mapper, atLeastOnce()).insert(any(RuleHitStat.class));
    }

    @Test
    void summaryShouldAggregateLatestRows() {
        RuleHitStat hit = new RuleHitStat();
        hit.setHitCount(3L);
        hit.setMissCount(1L);
        when(mapper.selectLatest(50)).thenReturn(List.of(hit));

        Map<String, Object> summary = service.summary();
        assertEquals(3L, summary.get("totalHit"));
        assertEquals(1L, summary.get("totalMiss"));
        assertTrue((Double) summary.get("hitRate") > 0);
    }

    @Test
    void flushShouldPersistBufferedCounts() {
        service.record("r2", "latency", true);
        service.flush();
        verify(mapper).insert(argThat(row ->
                "r2".equals(row.getRuleId()) && row.getHitCount() != null && row.getHitCount() > 0));
    }
}
