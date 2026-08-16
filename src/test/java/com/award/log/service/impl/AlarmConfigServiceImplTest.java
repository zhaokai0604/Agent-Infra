package com.award.log.service.impl;

import com.award.log.config.SystemConfigFileSupport;
import com.award.log.model.TaskAlarmConfig;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AlarmConfigServiceImplTest {

    @Test
    void saveDefaultConfigUpdatesDefaultLayer() throws Exception {
        Path tempDir = Files.createTempDirectory("alarm-config-test");
        String previous = System.getProperty("award.config.dir");
        try {
            System.setProperty("award.config.dir", tempDir.toString());
            AlarmConfigServiceImpl service = new AlarmConfigServiceImpl();

            TaskAlarmConfig saved = service.saveDefaultConfig(Map.of(
                    "alarmLevel", "warning",
                    "errorThreshold", 9,
                    "enabled", false,
                    "cooldownMs", 12345
            ));

            assertEquals("WARNING", saved.getAlarmLevel());
            assertEquals(9, saved.getErrorThreshold());
            assertEquals(Boolean.FALSE, saved.getEnabled());
            assertEquals(12345L, saved.getCooldownMs());

            Map<String, Object> root = SystemConfigFileSupport.readJsonFile(tempDir.resolve("alarm-config.json"));
            @SuppressWarnings("unchecked")
            Map<String, Object> defaults = (Map<String, Object>) root.get("default");
            assertEquals("WARNING", defaults.get("alarmLevel"));
            assertEquals(9, defaults.get("errorThreshold"));
            assertEquals(Boolean.FALSE, defaults.get("enabled"));
        } finally {
            if (previous == null) {
                System.clearProperty("award.config.dir");
            } else {
                System.setProperty("award.config.dir", previous);
            }
        }
    }

    @Test
    void saveTaskConfigRejectsBlankTaskId() {
        AlarmConfigServiceImpl service = new AlarmConfigServiceImpl();
        assertThrows(IllegalArgumentException.class, () -> service.saveTaskConfig(" ", Map.of()));
    }

    @Test
    void getDefaultConfigShouldReturnBuiltInDefaults() throws Exception {
        Path tempDir = Files.createTempDirectory("alarm-config-default");
        String previous = System.getProperty("award.config.dir");
        try {
            System.setProperty("award.config.dir", tempDir.toString());
            AlarmConfigServiceImpl service = new AlarmConfigServiceImpl();
            var config = service.getDefaultConfig();
            assertEquals("ERROR", config.getAlarmLevel());
            assertEquals(5, config.getErrorThreshold());
            assertTrue(config.getEnabled());
            assertEquals(300000L, config.getCooldownMs());
        } finally {
            restoreConfigDir(previous);
        }
    }

    @Test
    void getEffectiveConfigShouldMergeTaskOverrides() throws Exception {
        Path tempDir = Files.createTempDirectory("alarm-config-effective");
        String previous = System.getProperty("award.config.dir");
        try {
            System.setProperty("award.config.dir", tempDir.toString());
            AlarmConfigServiceImpl service = new AlarmConfigServiceImpl();
            service.saveTaskConfig("task-1", Map.of(
                    "alarmLevel", "warn",
                    "errorThreshold", 3,
                    "enabled", false));

            var effective = service.getEffectiveConfig("task-1");
            assertEquals("task-1", effective.getTaskId());
            assertEquals("WARNING", effective.getAlarmLevel());
            assertEquals(3, effective.getErrorThreshold());
            assertEquals(Boolean.FALSE, effective.getEnabled());
        } finally {
            restoreConfigDir(previous);
        }
    }

    @Test
    void normalizeLevelShouldMapWarnAndErrorLevelSuffix() throws Exception {
        Path tempDir = Files.createTempDirectory("alarm-config-level");
        String previous = System.getProperty("award.config.dir");
        try {
            System.setProperty("award.config.dir", tempDir.toString());
            AlarmConfigServiceImpl service = new AlarmConfigServiceImpl();
            var saved = service.saveDefaultConfig(Map.of("alarmLevel", "warn_level"));
            assertEquals("WARNING", saved.getAlarmLevel());
        } finally {
            restoreConfigDir(previous);
        }
    }

    private static void restoreConfigDir(String previous) {
        if (previous == null) {
            System.clearProperty("award.config.dir");
        } else {
            System.setProperty("award.config.dir", previous);
        }
    }
}
