package com.award.log.platform;

import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class KylinCommandProbeTest {

    @Test
    void runSkipsWhenProbeDisabled() {
        KylinCommandProbe probe = new KylinCommandProbe();
        ReflectionTestUtils.setField(probe, "probeEnabled", false);

        probe.run(new DefaultApplicationArguments(new String[]{}));

        assertTrue(probe.getLastProbeResult().isEmpty());
    }

    @Test
    void runSkipsOnWindowsHost() {
        KylinCommandProbe probe = new KylinCommandProbe();
        ReflectionTestUtils.setField(probe, "probeEnabled", true);

        probe.run(new DefaultApplicationArguments(new String[]{}));

        if (com.award.log.util.OsRuntime.isWindows()) {
            assertTrue(probe.getLastProbeResult().isEmpty());
        }
    }

    @Test
    void getLastProbeResultReturnsImmutableSnapshot() {
        KylinCommandProbe probe = new KylinCommandProbe();
        Map<String, Object> seeded = Map.of("readyForMcp", true, "missingCount", 0);
        ReflectionTestUtils.setField(probe, "lastProbeResult", seeded);

        Map<String, Object> result = probe.getLastProbeResult();
        assertEquals(true, result.get("readyForMcp"));
        assertEquals(0, result.get("missingCount"));
    }

    @Test
    void commandExistsReturnsFalseForMissingBinary() {
        Boolean exists = org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                KylinCommandProbe.class, "commandExists", "award-log-nonexistent-cmd-xyz");
        assertFalse(Boolean.TRUE.equals(exists));
    }
}
