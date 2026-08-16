package com.award.log.config;

import com.award.log.security.RequestUserResolver;
import com.award.log.service.impl.AiAuditLogService;
import com.award.log.support.PojoExerciseSupport;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cache.Cache;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ConfigPackageCoverageTest {

    @TempDir
    Path tempDir;

    @Test
    void propertiesBeansExposeDefaults() {
        StandardEnvironment env = new StandardEnvironment();
        PojoExerciseSupport.exerciseAll(
                AgentOpsProperties.class,
                KnowledgeProperties.class,
                OpsDryRunProperties.class,
                ManagementPortProperties.class,
                AppCorsProperties.class);

        SystemConfigRuntimeState runtimeState = new SystemConfigRuntimeState(env);
        runtimeState.init();
        assertFalse(runtimeState.getPatrolInspectRoots().isEmpty());
        assertEquals("HYBRID", runtimeState.getAutoRemediationMode());

        AgentOpsProperties agent = new AgentOpsProperties();
        assertNotNull(agent.getPaths());
        assertNotNull(agent.getServiceRestart());
        assertNotNull(agent.getLogSafety());

        ManagementPortProperties mgmt = new ManagementPortProperties();
        mgmt.setEnabled(true);
        mgmt.setPort(8089);
        assertTrue(mgmt.isActive(8088));
        assertFalse(mgmt.isActive(8089));
    }

    @Test
    void devProfileEnablesLocalAutoRemediationLoop() throws Exception {
        Map<String, Object> root = loadYamlResource("application-dev.yml");

        Map<String, Object> ops = mapAt(root, "ops");
        Map<String, Object> dryRun = mapAt(ops, "dry-run");
        Map<String, Object> auto = mapAt(ops, "auto-remediation");
        Map<String, Object> patrol = mapAt(ops, "patrol");
        Map<String, Object> agent = mapAt(root, "agent");
        Map<String, Object> paths = mapAt(agent, "paths");

        assertEquals(Boolean.FALSE, dryRun.get("global"));
        assertEquals(Boolean.TRUE, auto.get("enabled"));
        assertEquals("HYBRID", auto.get("run-mode"));
        assertEquals(Boolean.TRUE, auto.get("allow-above-confirm-max-in-pending"));
        assertEquals(1, asInt(auto.get("propose-temp-clean-disk-min")));
        assertEquals(1, asInt(auto.get("disk-temp-clean-min-percent")));
        assertEquals(60_000, asInt(auto.get("cooldown-ms")));
        assertEquals(Boolean.TRUE, patrol.get("enabled"));
        assertEquals(1, asInt(patrol.get("disk-warn-percent")));
        assertTrue(String.valueOf(patrol.get("inspect-roots")).contains("C:/Users/Administrator/AppData/Local/Temp"));
        assertTrue(String.valueOf(patrol.get("inspect-roots")).contains("C:/Windows/Temp"));
        assertTrue(listAt(paths, "windows-clean-roots").contains("C:/Windows/Temp"));
        assertTrue(listAt(paths, "windows-clean-roots").contains("C:/Users/Administrator/AppData/Local/Temp"));
        assertTrue(listAt(paths, "windows-log-cleanup-roots").contains("logs"));
        assertEquals(Boolean.TRUE, mapAt(agent, "runtime").get("enabled"));
        assertEquals(Boolean.TRUE, mapAt(agent, "autonomous").get("enabled"));
        assertEquals(Boolean.TRUE, mapAt(agent, "assistant").get("use-tool-agent-default"));
        assertEquals(Boolean.FALSE, mapAt(agent, "min-privilege").get("enabled"));
    }

    @Test
    void kylinProfileDefaultsToDedicatedLowPrivilegeUser() throws Exception {
        Map<String, Object> root = loadYamlResource("application-kylin.yml");
        Map<String, Object> agent = mapAt(root, "agent");
        Map<String, Object> ops = mapAt(root, "ops");

        assertEquals("${AGENT_RUN_AS_USER:award-agent}", agent.get("run-as-user"));
        assertEquals("${AGENT_MIN_PRIVILEGE:true}", mapAt(agent, "min-privilege").get("enabled"));
        assertEquals(Boolean.TRUE, mapAt(ops, "auto-remediation").get("enabled"));
        assertTrue(String.valueOf(mapAt(ops, "patrol").get("inspect-roots")).contains("/var/log"));
        assertTrue(listAt(mapAt(agent, "paths"), "clean-roots").contains("/var/cache"));
    }

    @Test
    void startupConfigValidatorWarnsInNonProd() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("test");
        env.setProperty("spring.datasource.url", "jdbc:h2:mem:test");
        env.setProperty("spring.datasource.username", "sa");

        StartupConfigValidator validator = new StartupConfigValidator(env);
        assertDoesNotThrow(validator::validate);
    }

    @Test
    void middlewarePostProcessorLoadsLocalConfigFromNestedProjectDir() throws Exception {
        Path project = tempDir.resolve("award-log");
        Path local = project.resolve("config").resolve("application-local.yml");
        Files.createDirectories(local.getParent());
        Files.writeString(project.resolve("pom.xml"), "<project/>");
        Files.writeString(local, """
                spring:
                  ai:
                    openai:
                      api-key: unit-local-key
                  datasource:
                    password: unit-db-pass
                """);

        String previousUserDir = System.getProperty("user.dir");
        System.setProperty("user.dir", tempDir.toString());
        try {
            MockEnvironment env = new MockEnvironment();
            new MiddlewareEnvironmentPostProcessor().postProcessEnvironment(env, new SpringApplication());

            assertEquals("unit-local-key", env.getProperty("spring.ai.openai.api-key"));
            assertEquals("unit-db-pass", env.getProperty("spring.datasource.password"));
            assertEquals("true", env.getProperty("award.local-config.loaded"));
            assertEquals(local.toAbsolutePath().normalize().toString(), env.getProperty("award.local-config.path"));
        } finally {
            System.setProperty("user.dir", previousUserDir);
        }
    }

    @Test
    void middlewarePostProcessorKeepsSystemPropertiesAboveLocalConfig() throws Exception {
        Path local = tempDir.resolve("config").resolve("application-local.yml");
        Files.createDirectories(local.getParent());
        Files.writeString(local, """
                spring:
                  ai:
                    openai:
                      api-key: local-should-not-win
                """);

        String previousUserDir = System.getProperty("user.dir");
        String previousApiKey = System.getProperty("spring.ai.openai.api-key");
        System.setProperty("user.dir", tempDir.toString());
        System.setProperty("spring.ai.openai.api-key", "system-property-wins");
        try {
            StandardEnvironment env = new StandardEnvironment();
            new MiddlewareEnvironmentPostProcessor().postProcessEnvironment(env, new SpringApplication());

            assertEquals("system-property-wins", env.getProperty("spring.ai.openai.api-key"));
            assertEquals("true", env.getProperty("award.local-config.loaded"));
        } finally {
            System.setProperty("user.dir", previousUserDir);
            if (previousApiKey == null) {
                System.clearProperty("spring.ai.openai.api-key");
            } else {
                System.setProperty("spring.ai.openai.api-key", previousApiKey);
            }
        }
    }

    @Test
    void middlewarePostProcessorIsRegisteredForSpringBootStartup() throws Exception {
        try (InputStream in = ConfigPackageCoverageTest.class.getClassLoader()
                .getResourceAsStream("META-INF/spring.factories")) {
            assertNotNull(in, "spring.factories should register startup processors");
            String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(content.contains("org.springframework.boot.env.EnvironmentPostProcessor"));
            assertTrue(content.contains(MiddlewareEnvironmentPostProcessor.class.getName()));
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> loadYamlResource(String name) throws IOException {
        try (InputStream in = ConfigPackageCoverageTest.class.getClassLoader().getResourceAsStream(name)) {
            assertNotNull(in, name + " should be on the test classpath");
            Object loaded = new Yaml().load(in);
            assertTrue(loaded instanceof Map<?, ?>, name + " should parse as a map");
            return (Map<String, Object>) loaded;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapAt(Map<String, Object> root, String key) {
        Object raw = root.get(key);
        assertTrue(raw instanceof Map<?, ?>, key + " should be a nested map");
        return (Map<String, Object>) raw;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> listAt(Map<String, Object> root, String key) {
        Object raw = root.get(key);
        assertTrue(raw instanceof List<?>, key + " should be a list");
        return (List<Object>) raw;
    }

    private static int asInt(Object value) {
        assertTrue(value instanceof Number, "expected numeric value but got " + value);
        return ((Number) value).intValue();
    }

    @Test
    void ttlCacheExpiresEntries() throws InterruptedException {
        TtlConcurrentMapCacheManager manager = new TtlConcurrentMapCacheManager(50L, "probe");
        Cache cache = manager.getCache("probe");
        assertNotNull(cache);
        cache.put("k", "v");
        assertNotNull(cache.get("k"));
        Thread.sleep(60);
        assertNull(cache.get("k"));
    }

    @Test
    void systemConfigFileSupportRoundTrip() throws Exception {
        Path file = tempDir.resolve("override.json");
        Map<String, Object> root = new LinkedHashMap<>();
        Map<String, Object> runtime = new LinkedHashMap<>();
        runtime.put("patrolInspectRoots", List.of("logs", "tmp"));
        runtime.put("healthCheckPorts", List.of(8088, 9090));
        runtime.put("dryRunGlobal", true);
        root.put("runtime", runtime);
        root.put("collector", Map.of("fileRoot", "logs"));
        root.put("ai", Map.of("baseUrl", "http://127.0.0.1:9", "chatModel", "demo"));

        SystemConfigFileSupport.writeJsonFile(file, root);
        Map<String, Object> read = SystemConfigFileSupport.readJsonFile(file);
        assertFalse(read.isEmpty());

        Map<String, Object> props = SystemConfigFileSupport.toSpringProperties(read, Map.of(), "secret");
        assertTrue(props.containsKey("ops.dry-run.global"));
        assertEquals("logs,tmp", props.get("ops.patrol.inspect-roots"));

        String cipher = SystemConfigFileSupport.encryptSecret("api-key-123", "unit-test-secret");
        String plain = SystemConfigFileSupport.decryptSecret(cipher, "unit-test-secret");
        assertEquals("api-key-123", plain);

        Map<String, Object> withSecret = SystemConfigFileSupport.toSpringProperties(
                read,
                Map.of("secretOps", Map.of("aiApiKey", cipher)),
                "unit-test-secret");
        assertEquals("api-key-123", withSecret.get("spring.ai.openai.api-key"));
        assertNotNull(SystemConfigFileSupport.latestSavedAt(file));
    }

    @Test
    void aiAuditFilterPersistsWhenServiceAvailable() throws ServletException, IOException {
        AiAuditLogService auditLogService = mock(AiAuditLogService.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<AiAuditLogService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(auditLogService);

        RequestUserResolver resolver = mock(RequestUserResolver.class);
        when(resolver.currentUserId(any())).thenReturn(42);
        when(resolver.currentUserRole(any())).thenReturn(1);

        AiAuditFilter filter = new AiAuditFilter(provider, resolver);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/mcp/tools");
        request.setRemoteAddr("127.0.0.1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (req, res) -> ((HttpServletResponse) res).setStatus(200);

        filter.doFilter(request, response, chain);

        verify(auditLogService).save(eq(42), eq(1), eq("127.0.0.1"), eq("GET"),
                eq("/api/mcp/tools"), eq(200), anyLong(), anyInt());
    }

    @Test
    void aiRateLimitFilterBlocksBurstRequests() throws Exception {
        AiRateLimitFilter filter = new AiRateLimitFilter();
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/award-log/api/assistant/chat/stream");
        request.setRemoteAddr("10.0.0.1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (req, res) -> ((HttpServletResponse) res).setStatus(200);

        for (int i = 0; i < 60; i++) {
            filter.doFilter(request, response, chain);
            assertEquals(200, response.getStatus());
        }
        filter.doFilter(request, response, chain);
        assertEquals(429, response.getStatus());
    }

    @Test
    void systemConfigHelpersHandleEdgeCases() {
        assertTrue(SystemConfigFileSupport.stringList("a,b").contains("a"));
        assertEquals(List.of(8088, 9090), SystemConfigFileSupport.integerList("8088,9090"));
        assertTrue(SystemConfigFileSupport.nestedMap(null).isEmpty());
        assertNotNull(SystemConfigFileSupport.defaultWorkingDir());
        assertFalse(SystemConfigFileSupport.candidateDirectories().isEmpty());
    }
}
