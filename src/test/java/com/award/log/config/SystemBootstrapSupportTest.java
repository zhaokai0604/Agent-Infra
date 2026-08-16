package com.award.log.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SystemBootstrapSupportTest {

    @TempDir
    Path tempDir;

    private final String previousConfigDir = System.getProperty("award.config.dir");

    @AfterEach
    void restoreConfigDir() {
        if (previousConfigDir == null) {
            System.clearProperty("award.config.dir");
        } else {
            System.setProperty("award.config.dir", previousConfigDir);
        }
    }

    @Test
    void reconcileForCurrentPlatformCorrectsWindowsDerivedDefaults() {
        System.setProperty("award.config.dir", tempDir.toString());
        SystemConfigFileSupport.writeJsonFile(SystemConfigFileSupport.overrideFile(), new LinkedHashMap<>(Map.of(
                "collector", Map.of("fileRoot", "/var/log"),
                "runtime", Map.of("patrolInspectRoots", List.of("/var/log")),
                "pathPolicy", Map.of(
                        "readPrefixes", List.of("/var/log"),
                        "cleanRoots", List.of("/tmp"),
                        "logCleanupRoots", List.of("/var/log/journal"))
        )));
        SystemConfigFileSupport.writeJsonFile(SystemConfigFileSupport.pathPolicyFile(), new LinkedHashMap<>(Map.of(
                "readPrefixes", List.of("/var/log"),
                "cleanRoots", List.of("/tmp"),
                "logCleanupRoots", List.of("/var/log/journal")
        )));

        Map<String, Object> status = SystemBootstrapSupport.reconcileForCurrentPlatform("unit-test");

        assertEquals("unit-test", status.get("source"));
        assertEquals("windows", status.get("platformKey"));
        assertEquals(Boolean.TRUE, status.get("corrected"));
        @SuppressWarnings("unchecked")
        List<String> changedKeys = (List<String>) status.get("changedKeys");
        assertTrue(changedKeys.contains("collector.fileRoot"));
        assertTrue(changedKeys.contains("runtime.patrolInspectRoots"));
        assertTrue(changedKeys.contains("pathPolicy.readPrefixes"));

        Map<String, Object> overrideRoot = SystemConfigFileSupport.readJsonFile(SystemConfigFileSupport.overrideFile());
        Map<String, Object> collector = SystemConfigFileSupport.nestedMap(overrideRoot.get("collector"));
        Map<String, Object> runtime = SystemConfigFileSupport.nestedMap(overrideRoot.get("runtime"));
        Map<String, Object> pathPolicy = SystemConfigFileSupport.nestedMap(overrideRoot.get("pathPolicy"));
        assertEquals("logs", collector.get("fileRoot"));
        assertTrue(SystemConfigFileSupport.stringList(runtime.get("patrolInspectRoots")).contains("C:/Windows/Logs"));
        assertTrue(SystemConfigFileSupport.stringList(pathPolicy.get("readPrefixes")).contains("C:/Windows/Logs"));

        Map<String, Object> policyFile = SystemConfigFileSupport.readJsonFile(SystemConfigFileSupport.pathPolicyFile());
        assertEquals("windows", policyFile.get("platform"));
        assertTrue(SystemConfigFileSupport.stringList(policyFile.get("cleanRoots"))
                .contains("C:/Users/Administrator/AppData/Local/Temp"));
    }

    @Test
    void readBootstrapStatusReturnsDefaultsWhenNoFilesExist() {
        System.setProperty("award.config.dir", tempDir.toString());

        Map<String, Object> status = SystemBootstrapSupport.readBootstrapStatus();

        assertEquals("windows", status.get("platformKey"));
        assertEquals(Boolean.FALSE, status.get("corrected"));
        assertEquals(Boolean.FALSE, status.get("overrideFileExists"));
        assertEquals(Boolean.FALSE, status.get("pathPolicyFileExists"));
        assertNotNull(status.get("capabilities"));
    }

    @Test
    void helperMethodsCoverBothPlatformShapes() throws Exception {
        assertEquals("logs", invokeStatic("defaultCollectorRoot", true));
        assertEquals("/var/log", invokeStatic("defaultCollectorRoot", false));
        assertTrue(((List<?>) invokeStatic("defaultInspectRoots", true)).contains("C:/Windows/Logs"));
        assertTrue(((List<?>) invokeStatic("defaultInspectRoots", false)).contains("/var/log"));
        assertTrue(((List<?>) invokeStatic("defaultReadPrefixes", false)).contains("/var/log/journal"));
        assertTrue(((List<?>) invokeStatic("defaultCleanRoots", true)).contains("C:/Temp"));
        assertTrue(((List<?>) invokeStatic("defaultLogCleanupRoots", false)).contains("/var/log"));
        assertTrue((Boolean) invokeStatic("looksMismatchedForPlatform", "/var/log", true));
        assertFalse((Boolean) invokeStatic("looksMismatchedForPlatform", "logs", true));
        assertTrue((Boolean) invokeStatic("looksMismatchedForPlatform", "C:/Windows/Logs", false));
        assertTrue((Boolean) invokeStatic("listLooksMismatchedForPlatform", List.of("/var/log"), true));
        assertFalse((Boolean) invokeStatic("listLooksMismatchedForPlatform", List.of("C:/Windows/Logs"), true));
        assertEquals(List.of("a", "b"), invokeStatic("dedupe", Arrays.asList(" a ", "a", "", null, "b")));
        assertFalse((Boolean) invokeStatic("hasCommand", " "));
        assertNotNull(SystemBootstrapSupport.buildCapabilitySnapshot());
    }

    private static Object invokeStatic(String methodName, Object... args) throws Exception {
        Method target = null;
        for (Method method : SystemBootstrapSupport.class.getDeclaredMethods()) {
            if (method.getName().equals(methodName) && method.getParameterCount() == args.length) {
                target = method;
                break;
            }
        }
        if (target == null) {
            throw new NoSuchMethodException(methodName);
        }
        target.setAccessible(true);
        return target.invoke(null, args);
    }
}
