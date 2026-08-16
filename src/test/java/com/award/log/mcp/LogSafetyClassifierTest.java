package com.award.log.mcp;

import com.award.log.config.AgentOpsProperties;
import com.award.log.security.OpsPathPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LogSafetyClassifierTest {

    private LogSafetyClassifier classifier;

    @BeforeEach
    void setUp() {
        OpsPathPolicy pathPolicy = mock(OpsPathPolicy.class);
        when(pathPolicy.getLogProtectedSubstrings()).thenReturn(
                AgentOpsProperties.Paths.linuxKylinDefaults().getLogProtectedSubstrings());
        AgentOpsProperties props = new AgentOpsProperties();
        classifier = new LogSafetyClassifier(pathPolicy, props);
    }

    @Test
    void deniesMysqlSlowLog() {
        LogSafetyClassifier.Verdict v = classifier.classifyPath("/var/log/mysql/slow.log");
        assertFalse(v.allowed());
    }

    @Test
    void allowsGenericRotatedLog() {
        LogSafetyClassifier.Verdict v = classifier.classifyPath("/var/log/nginx/access.log.1.gz");
        assertTrue(v.allowed());
    }

    @Test
    void filterSeparatesProtected() {
        LogSafetyClassifier.FilterResult r = classifier.filterDeletable(
                List.of("/var/log/app.log.2.gz", "/var/lib/mysql/ib_logfile0"));
        assertEquals(1, r.allowed().size());
        assertEquals(1, r.deniedReasons().size());
    }
}
