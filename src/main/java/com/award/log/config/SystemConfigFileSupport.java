package com.award.log.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 本地系统配置文件读写与敏感项加解密支撑。
 */
public final class SystemConfigFileSupport {

    public static final String OVERRIDE_FILE_NAME = "system-config-overrides.json";
    public static final String SECRET_FILE_NAME = "system-config-secrets.json";
    public static final String PATH_POLICY_FILE_NAME = "agent-path-policy-overrides.json";
    public static final String ALARM_CONFIG_FILE_NAME = "alarm-config.json";

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<LinkedHashMap<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private SystemConfigFileSupport() {
    }

    public static Path overrideFile() {
        return resolve(OVERRIDE_FILE_NAME);
    }

    public static Path secretFile() {
        return resolve(SECRET_FILE_NAME);
    }

    public static Path pathPolicyFile() {
        return resolve(PATH_POLICY_FILE_NAME);
    }

    public static Path alarmConfigFile() {
        return resolve(ALARM_CONFIG_FILE_NAME);
    }

    public static Path resolve(String fileName) {
        String configuredDir = configuredDir();
        if (configuredDir != null) {
            return Paths.get(configuredDir, fileName);
        }
        for (Path dir : candidateDirectories()) {
            Path candidate = dir.resolve(fileName);
            if (Files.exists(candidate)) {
                return candidate;
            }
        }
        return activeConfigDirPath().resolve(fileName);
    }

    public static List<Path> candidateDirectories() {
        List<Path> dirs = new ArrayList<>();
        addCandidate(dirs, defaultWorkingDir());
        addCandidate(dirs, classpathRootDir());
        return dirs;
    }

    public static Path defaultWorkingDir() {
        return Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
    }

    public static String activeConfigDir() {
        return activeConfigDirPath().toString();
    }

    public static Path activeConfigDirPath() {
        String configuredDir = configuredDir();
        if (configuredDir != null) {
            return Paths.get(configuredDir).toAbsolutePath().normalize();
        }
        Path workingDir = defaultWorkingDir();
        if (hasKnownConfigFiles(workingDir)) {
            return workingDir;
        }
        Path classpathDir = classpathRootDir();
        if (classpathDir != null && hasKnownConfigFiles(classpathDir)) {
            return classpathDir;
        }
        return workingDir;
    }

    private static Path classpathRootDir() {
        try {
            Path classesDir = Paths.get(SystemConfigFileSupport.class.getProtectionDomain()
                    .getCodeSource()
                    .getLocation()
                    .toURI());
            Path dir = Files.isDirectory(classesDir) ? classesDir : classesDir.getParent();
            if (dir == null) {
                return null;
            }
            Path current = dir.toAbsolutePath().normalize();
            while (current != null) {
                if (Files.exists(current.resolve("pom.xml")) || Files.exists(current.resolve(".git"))) {
                    return current;
                }
                current = current.getParent();
            }
            return dir.toAbsolutePath().normalize();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static void addCandidate(List<Path> dirs, Path path) {
        if (path == null) {
            return;
        }
        Path normalized = path.toAbsolutePath().normalize();
        if (!dirs.contains(normalized)) {
            dirs.add(normalized);
        }
    }

    private static boolean hasKnownConfigFiles(Path dir) {
        if (dir == null) {
            return false;
        }
        for (String fileName : knownConfigFileNames()) {
            if (Files.exists(dir.resolve(fileName))) {
                return true;
            }
        }
        return false;
    }

    private static List<String> knownConfigFileNames() {
        return List.of(
                OVERRIDE_FILE_NAME,
                SECRET_FILE_NAME,
                PATH_POLICY_FILE_NAME,
                ALARM_CONFIG_FILE_NAME);
    }

    private static String configuredDir() {
        return firstNonBlank(
                System.getProperty("award.config.dir"),
                System.getenv("AWARD_CONFIG_DIR"));
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    public static Map<String, Object> readJsonFile(Path path) {
        try {
            if (path == null || !Files.exists(path)) {
                return new LinkedHashMap<>();
            }
            return OBJECT_MAPPER.readValue(path.toFile(), MAP_TYPE);
        } catch (Exception ignored) {
            return new LinkedHashMap<>();
        }
    }

    public static void writeJsonFile(Path path, Map<String, Object> root) {
        try {
            if (path == null) {
                throw new IllegalArgumentException("path cannot be null");
            }
            Path parent = path.toAbsolutePath().normalize().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), root);
        } catch (Exception e) {
            throw new IllegalStateException("写入配置文件失败: " + e.getMessage(), e);
        }
    }

    public static Map<String, Object> toSpringProperties(Map<String, Object> overrideRoot,
                                                         Map<String, Object> secretRoot,
                                                         String appConfigSecret) {
        LinkedHashMap<String, Object> props = new LinkedHashMap<>();
        Map<String, Object> runtime = nestedMap(overrideRoot.get("runtime"));
        Map<String, Object> collector = nestedMap(overrideRoot.get("collector"));
        Map<String, Object> ai = nestedMap(overrideRoot.get("ai"));

        putCsv(props, "ops.patrol.inspect-roots", stringList(runtime.get("patrolInspectRoots")));
        putCsv(props, "agent.autonomous.health-check-ports", integerList(runtime.get("healthCheckPorts")));
        putString(props, "agent.autonomous.ping-target", runtime.get("pingTarget"));
        putBoolean(props, "ops.auto-remediation.enabled", runtime.get("autoRemediationEnabled"));
        putString(props, "ops.auto-remediation.run-mode", runtime.get("autoRemediationMode"));
        putBoolean(props, "ops.dry-run.global", runtime.get("dryRunGlobal"));
        putNumber(props, "ops.patrol.disk-warn-percent", runtime.get("patrolDiskWarnPercent"));
        putNumber(props, "ops.patrol.cpu-warn-percent", runtime.get("patrolCpuWarnPercent"));
        putNumber(props, "ops.patrol.anomaly-spike-factor", runtime.get("anomalySpikeFactor"));
        putNumber(props, "ops.patrol.error-alarm-min", runtime.get("errorAlarmMin"));
        putNumber(props, "ops.auto-remediation.risk-patrol-auto-max", runtime.get("autoRiskPatrolAutoMax"));
        putNumber(props, "ops.auto-remediation.propose-temp-clean-disk-min", runtime.get("autoProposeTempCleanDiskMin"));
        putNumber(props, "ops.auto-remediation.propose-log-clean-disk-min", runtime.get("autoProposeLogCleanDiskMin"));

        putString(props, "log.collector.file.path", collector.get("fileRoot"));
        putString(props, "log.collector.file.include-extensions", collector.get("includeExtensions"));
        putString(props, "log.collector.file.exclude-directories", collector.get("excludeDirectories"));
        putBoolean(props, "log.collector.network.enabled", collector.get("networkEnabled"));
        putNumber(props, "log.collector.network.port", collector.get("networkPort"));
        putString(props, "log.collector.network.protocol", collector.get("networkProtocol"));
        putBoolean(props, "log.collector.db.enabled", collector.get("dbEnabled"));
        putString(props, "log.collector.db.query", collector.get("dbQuery"));
        putString(props, "log.collector.db.checkpoint-file", collector.get("dbCheckpointFile"));

        putString(props, "spring.ai.openai.base-url", ai.get("baseUrl"));
        putString(props, "spring.ai.openai.chat.options.model", ai.get("chatModel"));
        putString(props, "spring.ai.openai.embedding.options.model", ai.get("embeddingModel"));

        Map<String, Object> secretOps = nestedMap(secretRoot.get("secretOps"));
        Object encrypted = secretOps.get("aiApiKey");
        if (encrypted instanceof String cipherText && !cipherText.isBlank() && appConfigSecret != null && !appConfigSecret.isBlank()) {
            try {
                props.put("spring.ai.openai.api-key", decryptSecret(cipherText, appConfigSecret));
            } catch (Exception ignored) {
                // Skip invalid encrypted secret to keep startup resilient.
            }
        }
        return props;
    }

    public static String encryptSecret(String plainText, String appConfigSecret) {
        if (plainText == null || plainText.isBlank()) {
            return "";
        }
        if (appConfigSecret == null || appConfigSecret.isBlank()) {
            throw new IllegalArgumentException("未设置 APP_CONFIG_SECRET，无法保存敏感配置");
        }
        try {
            byte[] iv = new byte[12];
            new SecureRandom().nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, secretKey(appConfigSecret), new GCMParameterSpec(128, iv));
            byte[] encrypted = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
            byte[] merged = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, merged, 0, iv.length);
            System.arraycopy(encrypted, 0, merged, iv.length, encrypted.length);
            return Base64.getEncoder().encodeToString(merged);
        } catch (Exception e) {
            throw new IllegalStateException("加密敏感配置失败: " + e.getMessage(), e);
        }
    }

    public static String decryptSecret(String cipherText, String appConfigSecret) {
        if (cipherText == null || cipherText.isBlank()) {
            return "";
        }
        if (appConfigSecret == null || appConfigSecret.isBlank()) {
            throw new IllegalArgumentException("未设置 APP_CONFIG_SECRET，无法解密敏感配置");
        }
        try {
            byte[] merged = Base64.getDecoder().decode(cipherText);
            byte[] iv = new byte[12];
            byte[] encrypted = new byte[merged.length - 12];
            System.arraycopy(merged, 0, iv, 0, 12);
            System.arraycopy(merged, 12, encrypted, 0, encrypted.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, secretKey(appConfigSecret), new GCMParameterSpec(128, iv));
            byte[] plain = cipher.doFinal(encrypted);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("解密敏感配置失败: " + e.getMessage(), e);
        }
    }

    public static String latestSavedAt(Path... paths) {
        Instant latest = null;
        for (Path path : paths) {
            try {
                if (path != null && Files.exists(path)) {
                    Instant current = Files.getLastModifiedTime(path).toInstant();
                    if (latest == null || current.isAfter(latest)) {
                        latest = current;
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return latest == null ? null : latest.toString();
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> nestedMap(Object raw) {
        if (raw instanceof Map<?, ?> map) {
            LinkedHashMap<String, Object> out = new LinkedHashMap<>();
            map.forEach((k, v) -> {
                if (k != null) {
                    out.put(String.valueOf(k), v);
                }
            });
            return out;
        }
        return new LinkedHashMap<>();
    }

    public static List<String> stringList(Object raw) {
        if (raw == null) {
            return List.of();
        }
        if (raw instanceof List<?> list) {
            List<String> out = new ArrayList<>();
            for (Object item : list) {
                if (item != null) {
                    String value = String.valueOf(item).trim();
                    if (!value.isEmpty()) {
                        out.add(value);
                    }
                }
            }
            return out;
        }
        String asString = String.valueOf(raw).trim();
        if (asString.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String part : asString.split(",")) {
            String value = part.trim();
            if (!value.isEmpty()) {
                out.add(value);
            }
        }
        return out;
    }

    public static List<Integer> integerList(Object raw) {
        if (raw == null) {
            return List.of();
        }
        List<String> rawList = stringList(raw);
        List<Integer> out = new ArrayList<>();
        for (String part : rawList) {
            out.add(Integer.parseInt(part));
        }
        return out;
    }

    private static void putString(Map<String, Object> target, String key, Object value) {
        if (value != null) {
            String normalized = String.valueOf(value).trim();
            if (!normalized.isEmpty()) {
                target.put(key, normalized);
            }
        }
    }

    private static void putBoolean(Map<String, Object> target, String key, Object value) {
        if (value != null) {
            target.put(key, Boolean.parseBoolean(String.valueOf(value)));
        }
    }

    private static void putNumber(Map<String, Object> target, String key, Object value) {
        if (value != null && !String.valueOf(value).isBlank()) {
            target.put(key, String.valueOf(value).trim());
        }
    }

    private static void putCsv(Map<String, Object> target, String key, List<?> values) {
        if (values != null && !values.isEmpty()) {
            target.put(key, values.stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse(""));
        }
    }

    private static SecretKeySpec secretKey(String appConfigSecret) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(appConfigSecret.getBytes(StandardCharsets.UTF_8));
        byte[] key = new byte[16];
        System.arraycopy(digest, 0, key, 0, key.length);
        return new SecretKeySpec(key, "AES");
    }
}
