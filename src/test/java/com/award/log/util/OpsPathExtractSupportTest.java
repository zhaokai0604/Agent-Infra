package com.award.log.util;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpsPathExtractSupportTest {

    @Test
    void bestPath_extractsFullWindowsTempSubdirectory() {
        String msg = "删除 C:\\Users\\Administrator\\AppData\\Local\\Temp\\trae-cn-user-x64";
        Optional<String> path = OpsPathExtractSupport.bestPath(msg);
        assertTrue(path.isPresent());
        assertEquals(
                "C:\\Users\\Administrator\\AppData\\Local\\Temp\\trae-cn-user-x64",
                path.get());
    }

    @Test
    void bestPath_prefersLongestCandidate() {
        String msg = "C:\\Users 与 C:\\Users\\Administrator\\AppData\\Local\\Temp\\foo";
        Optional<String> path = OpsPathExtractSupport.bestPath(msg);
        assertTrue(path.isPresent());
        assertEquals("C:\\Users\\Administrator\\AppData\\Local\\Temp\\foo", path.get());
    }

    @Test
    void bestPathFromConversation_usesPriorUserMessage() {
        Optional<String> path = OpsPathExtractSupport.bestPathFromConversation(
                "直接删除",
                List.of("删除 C:\\Users\\Administrator\\AppData\\Local\\Temp\\trae-cn-user-x64"));
        assertTrue(path.isPresent());
        assertTrue(path.get().contains("trae-cn-user-x64"));
    }
}
