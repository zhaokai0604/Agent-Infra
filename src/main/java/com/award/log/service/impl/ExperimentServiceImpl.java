package com.award.log.service.impl;

import com.award.log.service.ExperimentService;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class ExperimentServiceImpl implements ExperimentService {

    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile String experimentName = "";
    private final AtomicLong sampleCount = new AtomicLong(0);
    private final AtomicLong diffCount = new AtomicLong(0);

    @Override
    public boolean start(String name) {
        experimentName = name;
        sampleCount.set(0);
        diffCount.set(0);
        return running.compareAndSet(false, true);
    }

    @Override
    public boolean stop() {
        return running.compareAndSet(true, false);
    }

    @Override
    public Map<String, Object> report() {
        long total = sampleCount.get();
        long diff = diffCount.get();
        return Map.of(
                "experimentName", experimentName,
                "running", running.get(),
                "sampleCount", total,
                "diffCount", diff,
                "diffRate", diff / (double) Math.max(1, total)
        );
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }
}
