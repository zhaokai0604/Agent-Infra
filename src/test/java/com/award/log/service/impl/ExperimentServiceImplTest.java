package com.award.log.service.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class ExperimentServiceImplTest {

    private ExperimentServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ExperimentServiceImpl();
    }

    @Test
    void startShouldActivateExperiment() {
        assertTrue(service.start("shadow-test"));
        assertTrue(service.isRunning());
        assertFalse(service.start("another"));
    }

    @Test
    void stopShouldDeactivateExperiment() {
        service.start("run-a");
        assertTrue(service.stop());
        assertFalse(service.isRunning());
    }

    @Test
    void reportShouldExposeRunningState() {
        service.start("baseline");
        Map<String, Object> report = service.report();
        assertEquals("baseline", report.get("experimentName"));
        assertEquals(Boolean.TRUE, report.get("running"));
        assertEquals(0L, report.get("sampleCount"));
    }

    @Test
    void reportShouldComputeDiffRateSafely() {
        Map<String, Object> report = service.report();
        assertEquals(0.0, report.get("diffRate"));
    }
}
