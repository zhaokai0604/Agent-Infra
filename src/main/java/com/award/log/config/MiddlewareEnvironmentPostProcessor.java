package com.award.log.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.Ordered;
import org.springframework.core.env.CompositePropertySource;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.FileSystemResource;
import org.springframework.util.StringUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 在绑定完 application*.yml 后合并 {@code spring.autoconfigure.exclude}，
 * 使 Kafka / Redis 在未开启时不加载自动配置，避免启动期连接与错误日志。
 */
public class MiddlewareEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    private static final String EXCLUDE_KEY = "spring.autoconfigure.exclude";
    private static final String LOCAL_CONFIG_NAME = "application-local.yml";
    private static final String LOCAL_CONFIG_SOURCE = "award-local-application-config";
    private static final String LOCAL_CONFIG_PATH_KEY = "award.local-config.path";
    private static final String LOCAL_CONFIG_LOADED_KEY = "award.local-config.loaded";
    private static final List<String> PROJECT_DIR_NAMES = List.of(
            "ThreshCore代码",
            "ThreshCore 代码",
            "ThreshCore",
            "award-log");

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        SystemBootstrapSupport.reconcileForCurrentPlatform("startup-auto-reconcile");
        CompositePropertySource localConfigSource = loadLocalApplicationConfigSource(environment);
        if (localConfigSource != null) {
            addLocalConfigSource(environment, localConfigSource);
        }
        MapPropertySource systemConfigSource = loadSystemConfigSource(environment);
        if (systemConfigSource != null) {
            environment.getPropertySources().addFirst(systemConfigSource);
        }

        boolean kafkaOn = Boolean.parseBoolean(environment.getProperty("award.middleware.kafka", "false"));
        boolean redisOn = Boolean.parseBoolean(environment.getProperty("award.middleware.redis", "false"));
        boolean elasticsearchOn = Boolean.parseBoolean(environment.getProperty("spring.elasticsearch.enabled", "false"));

        LinkedHashSet<String> merged = new LinkedHashSet<>();
        Binder binder = Binder.get(environment);
        List<String> existing = binder.bind(EXCLUDE_KEY, Bindable.listOf(String.class)).orElseGet(ArrayList::new);
        for (String s : existing) {
            if (StringUtils.hasText(s)) {
                merged.add(s.trim());
            }
        }
        if (merged.isEmpty()) {
            String csvExisting = environment.getProperty(EXCLUDE_KEY);
            if (StringUtils.hasText(csvExisting)) {
                for (String part : csvExisting.split(",")) {
                    if (StringUtils.hasText(part)) {
                        merged.add(part.trim());
                    }
                }
            }
        }

        if (!kafkaOn) {
            merged.add("org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration");
        }
        if (!redisOn) {
            merged.add("org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration");
            merged.add("org.springframework.boot.autoconfigure.data.redis.RedisReactiveAutoConfiguration");
            merged.add("org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration");
        }
        if (!elasticsearchOn) {
            merged.add("org.springframework.boot.autoconfigure.elasticsearch.ElasticsearchClientAutoConfiguration");
            merged.add("org.springframework.boot.autoconfigure.elasticsearch.ElasticsearchRestClientAutoConfiguration");
            merged.add("org.springframework.boot.autoconfigure.elasticsearch.ReactiveElasticsearchClientAutoConfiguration");
            merged.add("org.springframework.boot.autoconfigure.data.elasticsearch.ElasticsearchDataAutoConfiguration");
            merged.add("org.springframework.boot.autoconfigure.data.elasticsearch.ElasticsearchRepositoriesAutoConfiguration");
            merged.add("org.springframework.boot.autoconfigure.data.elasticsearch.ReactiveElasticsearchRepositoriesAutoConfiguration");
        }

        String csv = merged.stream().collect(Collectors.joining(","));
        java.util.Map<String, Object> properties = new java.util.LinkedHashMap<>();
        properties.put(EXCLUDE_KEY, csv);
        if (!redisOn) {
            properties.put("management.health.redis.enabled", "false");
        }
        if (!elasticsearchOn) {
            properties.put("management.health.elasticsearch.enabled", "false");
        }
        environment.getPropertySources().addFirst(new MapPropertySource(
                "award-middleware-autoconfigure-exclude",
                properties));
    }

    private CompositePropertySource loadLocalApplicationConfigSource(ConfigurableEnvironment environment) {
        Path config = findLocalApplicationConfig(environment);
        if (config == null) {
            return null;
        }
        try {
            List<PropertySource<?>> loaded = new YamlPropertySourceLoader().load(
                    LOCAL_CONFIG_SOURCE + "-" + config.getFileName(),
                    new FileSystemResource(config));
            if (loaded.isEmpty()) {
                return null;
            }
            CompositePropertySource composite = new CompositePropertySource(LOCAL_CONFIG_SOURCE);
            composite.addPropertySource(new MapPropertySource(LOCAL_CONFIG_SOURCE + "-metadata", Map.of(
                    LOCAL_CONFIG_PATH_KEY, config.toString(),
                    LOCAL_CONFIG_LOADED_KEY, "true")));
            for (PropertySource<?> source : loaded) {
                composite.addPropertySource(source);
            }
            return composite;
        } catch (Exception ignored) {
            return null;
        }
    }

    private Path findLocalApplicationConfig(ConfigurableEnvironment environment) {
        LinkedHashSet<Path> candidates = new LinkedHashSet<>();
        addExplicitLocalConfigCandidates(candidates, environment);

        LinkedHashSet<Path> baseDirs = new LinkedHashSet<>();
        addBaseDir(baseDirs, SystemConfigFileSupport.defaultWorkingDir());
        addBaseDir(baseDirs, classpathLocationDir());

        List<Path> initialBases = new ArrayList<>(baseDirs);
        for (Path base : initialBases) {
            addParentDirs(baseDirs, base, 5);
        }

        for (Path base : new ArrayList<>(baseDirs)) {
            addApplicationLocalCandidates(candidates, base);
            for (String projectDirName : PROJECT_DIR_NAMES) {
                addApplicationLocalCandidates(candidates, base.resolve(projectDirName));
            }
        }

        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                return candidate.toAbsolutePath().normalize();
            }
        }
        return null;
    }

    private void addExplicitLocalConfigCandidates(LinkedHashSet<Path> candidates, ConfigurableEnvironment environment) {
        String configuredFile = firstNonBlank(
                environment.getProperty("threshcore.config"),
                environment.getProperty("award.local-config.file"),
                System.getProperty("threshcore.config"),
                System.getProperty("award.local-config.file"),
                System.getenv("THRESHCORE_CONFIG"),
                System.getenv("AWARD_LOCAL_CONFIG_FILE"));
        if (StringUtils.hasText(configuredFile)) {
            addConfiguredPath(candidates, environment, configuredFile);
        }

        String[] configuredDirs = {
                environment.getProperty("threshcore.config.dir"),
                environment.getProperty("award.config.dir"),
                System.getProperty("threshcore.config.dir"),
                System.getProperty("award.config.dir"),
                System.getenv("THRESHCORE_CONFIG_DIR"),
                System.getenv("AWARD_CONFIG_DIR")
        };
        for (String configuredDir : configuredDirs) {
            if (StringUtils.hasText(configuredDir)) {
                addApplicationLocalCandidates(candidates, pathOf(environment.resolvePlaceholders(configuredDir)));
            }
        }
    }

    private void addConfiguredPath(LinkedHashSet<Path> candidates,
                                   ConfigurableEnvironment environment,
                                   String configuredPath) {
        Path path = pathOf(environment.resolvePlaceholders(configuredPath));
        if (path == null) {
            return;
        }
        if (Files.isDirectory(path)) {
            addApplicationLocalCandidates(candidates, path);
        } else {
            addCandidate(candidates, path);
        }
    }

    private void addApplicationLocalCandidates(LinkedHashSet<Path> candidates, Path base) {
        if (base == null) {
            return;
        }
        addCandidate(candidates, base.resolve(LOCAL_CONFIG_NAME));
        addCandidate(candidates, base.resolve("config").resolve(LOCAL_CONFIG_NAME));
    }

    private void addParentDirs(LinkedHashSet<Path> baseDirs, Path start, int maxDepth) {
        Path current = start;
        for (int i = 0; i < maxDepth && current != null; i++) {
            addBaseDir(baseDirs, current);
            current = current.getParent();
        }
    }

    private void addLocalConfigSource(ConfigurableEnvironment environment, CompositePropertySource source) {
        MutablePropertySources sources = environment.getPropertySources();
        if (sources.contains(LOCAL_CONFIG_SOURCE)) {
            sources.replace(LOCAL_CONFIG_SOURCE, source);
            return;
        }
        if (sources.contains(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME)) {
            sources.addAfter(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, source);
            return;
        }
        if (sources.contains(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME)) {
            sources.addAfter(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME, source);
            return;
        }
        if (sources.contains("mockProperties")) {
            sources.addAfter("mockProperties", source);
            return;
        }
        sources.addFirst(source);
    }

    private MapPropertySource loadSystemConfigSource(ConfigurableEnvironment environment) {
        String appConfigSecret = environment.getProperty("APP_CONFIG_SECRET", "");
        Map<String, Object> props = SystemConfigFileSupport.toSpringProperties(
                SystemConfigFileSupport.readJsonFile(SystemConfigFileSupport.overrideFile()),
                SystemConfigFileSupport.readJsonFile(SystemConfigFileSupport.secretFile()),
                appConfigSecret);
        if (props.isEmpty()) {
            return null;
        }
        return new MapPropertySource("award-system-config-overrides", props);
    }

    private static Path classpathLocationDir() {
        try {
            Path location = Paths.get(MiddlewareEnvironmentPostProcessor.class.getProtectionDomain()
                    .getCodeSource()
                    .getLocation()
                    .toURI());
            Path dir = Files.isDirectory(location) ? location : location.getParent();
            return dir == null ? null : dir.toAbsolutePath().normalize();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static void addBaseDir(LinkedHashSet<Path> dirs, Path path) {
        Path normalized = normalize(path);
        if (normalized != null) {
            dirs.add(normalized);
        }
    }

    private static void addCandidate(LinkedHashSet<Path> candidates, Path path) {
        Path normalized = normalize(path);
        if (normalized != null) {
            candidates.add(normalized);
        }
    }

    private static Path pathOf(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        try {
            return Paths.get(raw.trim());
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Path normalize(Path path) {
        if (path == null) {
            return null;
        }
        try {
            return path.toAbsolutePath().normalize();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return null;
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE - 20;
    }
}
