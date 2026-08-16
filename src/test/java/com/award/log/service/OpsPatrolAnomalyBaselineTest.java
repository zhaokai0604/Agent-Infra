package com.award.log.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 巡检异常基线策略（与 {@link OpsPatrolService} 内 applyAnomalyBaseline 逻辑对齐的纯函数版）。
 */
class OpsPatrolAnomalyBaselineTest {

    @Test
    void seedThenEmaWithoutSpike() {
        BaselineState s = new BaselineState();
        s.apply(100, 2.0, 5, 0.35);
        assertEquals("seed", s.lastAction);
        assertEquals(100, s.baseline);

        s.apply(110, 2.0, 5, 0.35);
        assertEquals("ema_update", s.lastAction);
        assertTrue(s.baseline >= 103 && s.baseline <= 104);
    }

    @Test
    void spikeHoldsBaseline() {
        BaselineState s = new BaselineState();
        s.apply(50, 2.0, 5, 0.35);
        s.apply(120, 2.0, 5, 0.35);
        assertEquals("hold_on_spike", s.lastAction);
        assertEquals(50, s.baseline);
        assertTrue(s.lastSpike);
    }

    @Test
    void windowShrinkResetsBaseline() {
        BaselineState s = new BaselineState();
        s.apply(200, 2.0, 5, 0.35);
        s.apply(80, 2.0, 5, 0.35);
        assertEquals("reset_window_shrink", s.lastAction);
        assertEquals(80, s.baseline);
        assertFalse(s.lastSpike);
    }

  /** 镜像生产基线状态机，便于单测 */
    static final class BaselineState {
        long baseline = -1;
        boolean initialized;
        String lastAction = "";
        boolean lastSpike;

        void apply(long anomalyTotal, double spikeFactor, int minDelta, double emaAlpha) {
            lastSpike = false;
            if (!initialized) {
                baseline = anomalyTotal;
                initialized = true;
                lastAction = "seed";
                return;
            }
            long prev = baseline;
            boolean windowShrink = prev > 0 && anomalyTotal < prev * 0.55;
            boolean spike = !windowShrink
                    && prev > 0
                    && anomalyTotal >= prev * spikeFactor
                    && anomalyTotal - prev >= minDelta;
            if (spike) {
                lastAction = "hold_on_spike";
                lastSpike = true;
                return;
            }
            if (windowShrink) {
                baseline = anomalyTotal;
                lastAction = "reset_window_shrink";
                return;
            }
            double alpha = Math.min(1.0, Math.max(0.05, emaAlpha));
            baseline = Math.round(prev * (1.0 - alpha) + anomalyTotal * alpha);
            lastAction = "ema_update";
        }
    }
}
