package com.award.log.util;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class OsRuntimeTest {

    @Test
    void parsesQuotedKylinOsReleaseFields() {
        Map<String, String> parsed = OsReleaseParser.parse(""
                + "NAME=\"Kylin Linux Advanced Server\"\n"
                + "ID=kylin\n"
                + "VERSION_ID=\"V11\"\n"
                + "VARIANT=Server\n");

        assertEquals("kylin", parsed.get("ID"));
        assertEquals("V11", parsed.get("VERSION_ID"));
        assertTrue(OsReleaseParser.isKylin(parsed));
    }

    @Test
    void platformSummaryContainsRequiredKeys() {
        Map<String, Object> s = OsRuntime.platformSummary();
        assertNotNull(s.get("osName"));
        assertNotNull(s.get("osArch"));
        assertTrue(s.containsKey("kylin"));
        assertTrue(s.containsKey("loongArch"));
        assertEquals("Kylin-V11-LoongArch64", s.get("deliveryTarget"));
    }

    @Test
    void windowsVsUnixMutuallyExclusive() {
        if (OsRuntime.isWindows()) {
            assertFalse(OsRuntime.isUnixLike());
        } else {
            assertTrue(OsRuntime.isUnixLike());
        }
    }
}
