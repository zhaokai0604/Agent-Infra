package com.award.log.agent.awm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AwmToolProfileTest {

    @Test
    void supportsExpandedReadOnlyTools() {
        assertTrue(AwmToolProfile.isSupported("NetworkTool"));
        assertTrue(AwmToolProfile.isSupported("PortHealthTool"));
        assertTrue(AwmToolProfile.isSupported("DockerTool"));
        assertTrue(AwmToolProfile.isReadOnly("LogAnalysisTool"));
    }

    @Test
    void normalizesShortNames() {
        assertEquals("NetworkTool", AwmToolProfile.normalize("Network"));
        assertEquals("CleanTempTool", AwmToolProfile.normalize("CleanTemp"));
    }

    @Test
    void supportedToolCountAtLeastFifteen() {
        assertTrue(AwmToolProfile.supportedTools().size() >= 15);
    }
}
