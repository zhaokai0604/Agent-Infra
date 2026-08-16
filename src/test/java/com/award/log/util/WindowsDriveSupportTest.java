package com.award.log.util;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class WindowsDriveSupportTest {

    @Test
    void listLogicalDrives_nonEmptyOnWindows() {
        assumeTrue(OsRuntime.isWindows());
        List<String> drives = WindowsDriveSupport.listLogicalDrives();
        assertFalse(drives.isEmpty());
        assertFalse(WindowsDriveSupport.discoverTempCleanCandidates().isEmpty());
    }
}
