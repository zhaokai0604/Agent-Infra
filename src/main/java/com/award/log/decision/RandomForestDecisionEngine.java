package com.award.log.decision;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import com.award.log.util.OsRuntime;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 随机森林推理引擎（ONNX Runtime）
 * 模型缺失或推理失败时自动降级到启发式评分。
 */
@Slf4j
@Component
public class RandomForestDecisionEngine {

    @Value("${log.pipeline.decision.rf.model-path:src/main/resources/models/random-forest-v2.onnx}")
    private String modelPath;

    @Value("${log.pipeline.decision.rf.high-confidence:0.8}")
    private double highConfidence;

    @Value("${log.pipeline.decision.rf.alert-threshold:0.75}")
    private double alertThreshold;

    @Value("${log.pipeline.decision.feature-version:rf-v2}")
    private String featureVersion;

    @Value("${log.pipeline.decision.rf.model-version:rf-onnx-v2}")
    private String modelVersion;

    @Value("${log.pipeline.decision.rf.force-heuristic-on-loongarch:false}")
    private boolean forceHeuristicOnLoongarch;

    private volatile OrtEnvironment environment;
    private volatile OrtSession session;
    private volatile String inputName;
    private volatile long lastInferenceTime = 0L;
    private volatile boolean lastInferenceSuccess = false;
    private volatile String lastInferenceError = "";
    private volatile double lastInferenceScore = 0D;
    private volatile long lastInferenceLatencyMs = 0L;
    private volatile boolean modelLoaded = false;
    private volatile String modelStatus = "INIT";

    private WatchService watchService;
    private Thread watchThread;
    private final AtomicBoolean watcherRunning = new AtomicBoolean(false);
    private final AtomicLong inferenceCounter = new AtomicLong(0);
    private final float[] featureAbsSum = new float[RfFeatureVectorExt.FEATURE_SIZE];
    private final String[] featureNames = new String[]{
            "levelTrace", "levelDebug", "levelInfo", "levelWarn", "levelErrorFatal",
            "intervalMean", "intervalVar", "intervalMax", "intervalMin",
            "templateHash0", "templateHash1", "templateHash2", "templateHash3", "templateHash4",
            "tfidfError", "tfidfException", "tfidfTimeout",
            "errorRate1m", "errorNorm1m", "totalNorm1m",
            "callDepth", "entropy", "protocolDb", "protocolHttp", "protocolOther"
    };

    @PostConstruct
    public void init() {
        tryLoadModel();
        startWatcher();
    }

    public DecisionResult evaluate(DecisionInput input) {
        long start = System.currentTimeMillis();
        double score;
        String reason;
        if (tryLoadModel()) {
            try {
                score = runOnnxInference(input);
                reason = "随机森林(ONNX)推理结果";
            } catch (Exception e) {
                log.warn("[RF] ONNX推理失败，回退启发式评分: {}", e.getMessage());
                score = pseudoScore(input);
                reason = "随机森林启发式评分(ONNX推理失败回退)";
            }
        } else {
            score = pseudoScore(input);
            reason = "随机森林启发式评分(模型未就绪)";
        }

        boolean shouldAlert = score >= alertThreshold;
        lastInferenceLatencyMs = System.currentTimeMillis() - start;

        return DecisionResult.builder()
                .engineType(EngineType.RANDOM_FOREST)
                .shouldAlert(shouldAlert)
                .confidence(shouldAlert ? Math.max(highConfidence, score) : score)
                .featureVersion(featureVersion)
                .modelVersion(modelVersion)
                .reason(reason)
                .recommendation("持续补充标注样本并按周更新模型版本")
                .build();
    }

    private boolean tryLoadModel() {
        if (forceHeuristicOnLoongarch && OsRuntime.isLoongArch()) {
            modelLoaded = false;
            modelStatus = "HEURISTIC_LOONGARCH";
            return false;
        }
        if (session != null && inputName != null) {
            return true;
        }
        synchronized (this) {
            if (session != null && inputName != null) {
                return true;
            }
            Path resolved = resolveModelPath();
            if (resolved == null || !Files.exists(resolved)) {
                modelLoaded = false;
                modelStatus = "MODEL_NOT_FOUND";
                return false;
            }
            try {
                environment = OrtEnvironment.getEnvironment();
                OrtSession.SessionOptions options = new OrtSession.SessionOptions();
                session = environment.createSession(resolved.toString(), options);
                inputName = session.getInputNames().stream().findFirst().orElse(null);
                if (inputName == null) {
                    closeSession();
                    modelLoaded = false;
                    modelStatus = "INPUT_NAME_MISSING";
                    return false;
                }
                log.info("[RF] ONNX模型加载成功: {}", modelPath);
                modelLoaded = true;
                modelStatus = "READY";
                return true;
            } catch (Exception e) {
                log.warn("[RF] ONNX模型加载失败，使用回退评分: {}", e.getMessage());
                closeSession();
                modelLoaded = false;
                modelStatus = "LOAD_FAILED";
                return false;
            }
        }
    }

    private Path resolveModelPath() {
        if (modelPath == null || modelPath.isBlank()) {
            return null;
        }
        if (modelPath.startsWith("classpath:")) {
            String resource = modelPath.substring("classpath:".length()).trim();
            try {
                java.net.URL url = getClass().getClassLoader().getResource(resource);
                if (url != null) {
                    return Path.of(url.toURI());
                }
            } catch (Exception e) {
                log.debug("[RF] classpath 模型解析失败: {}", e.getMessage());
            }
            return null;
        }
        return Path.of(modelPath);
    }

    private double runOnnxInference(DecisionInput input) throws OrtException {
        float[] features = RfFeatureVectorExt.fromDecisionInputExt(input).toArray();
        trackFeatureImportance(features);
        long[] shape = new long[]{1, features.length};
        try (OnnxTensor tensor = OnnxTensor.createTensor(environment, FloatBuffer.wrap(features), shape);
             OrtSession.Result result = session.run(Collections.singletonMap(inputName, tensor))) {

            for (Map.Entry<String, OnnxValue> entry : result) {
                Object value = entry.getValue().getValue();
                Double score = tryExtractScore(value);
                if (score != null) {
                    double normalized = clamp(score);
                    markInference(normalized, true, "");
                    return normalized;
                }
            }
        }
        markInference(0D, false, "无法从ONNX输出提取评分");
        throw new OrtException("未能从ONNX输出中提取评分");
    }

    private void trackFeatureImportance(float[] features) {
        for (int i = 0; i < features.length; i++) {
            featureAbsSum[i] += Math.abs(features[i]);
        }
        long count = inferenceCounter.incrementAndGet();
        if (count % 1000 == 0) {
            Map<String, Float> scoreMap = new HashMap<>();
            for (int i = 0; i < featureNames.length; i++) {
                scoreMap.put(featureNames[i], featureAbsSum[i] / count);
            }
            List<Map.Entry<String, Float>> top = scoreMap.entrySet().stream()
                    .sorted(Map.Entry.<String, Float>comparingByValue(Comparator.reverseOrder()))
                    .limit(5)
                    .toList();
            log.info("[RF] 近{}次推理Top-5特征重要性(近似): {}", count, top);
        }
    }

    private Double tryExtractScore(Object value) {
        if (value instanceof float[][] arr && arr.length > 0 && arr[0].length > 0) {
            if (arr[0].length == 1) {
                return (double) arr[0][0];
            }
            return (double) arr[0][arr[0].length - 1];
        }
        if (value instanceof float[] arr && arr.length > 0) {
            return (double) arr[arr.length - 1];
        }
        if (value instanceof long[] arr && arr.length > 0) {
            return arr[0] > 0 ? 1.0 : 0.0;
        }
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        return null;
    }

    private double clamp(double score) {
        if (Double.isNaN(score) || Double.isInfinite(score)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, score));
    }

    private double pseudoScore(DecisionInput input) {
        double score = 0.0;
        if ("FATAL".equals(input.getEvent().getLevel())) score += 0.45;
        else if ("ERROR".equals(input.getEvent().getLevel())) score += 0.35;
        score += Math.min(0.4, input.getErrorRate1m());
        if (input.getTemplate() != null && input.getTemplate().toLowerCase().contains("exception")) score += 0.15;
        double fallback = Math.min(0.98, score);
        markInference(fallback, false, "fallback");
        return fallback;
    }

    public Map<String, Object> healthSnapshot() {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("modelPath", modelPath);
        snapshot.put("modelVersion", modelVersion);
        snapshot.put("featureVersion", featureVersion);
        snapshot.put("modelLoaded", session != null && inputName != null && modelLoaded);
        snapshot.put("modelStatus", modelStatus);
        snapshot.put("loongArch", OsRuntime.isLoongArch());
        snapshot.put("forceHeuristicOnLoongarch", forceHeuristicOnLoongarch);
        snapshot.put("inputName", inputName == null ? "" : inputName);
        snapshot.put("featureSize", RfFeatureVectorExt.FEATURE_SIZE);
        snapshot.put("lastInferenceTime", lastInferenceTime);
        snapshot.put("lastInferenceSuccess", lastInferenceSuccess);
        snapshot.put("lastInferenceLatencyMs", lastInferenceLatencyMs);
        snapshot.put("lastInferenceScore", lastInferenceScore);
        snapshot.put("lastInferenceError", lastInferenceError == null ? "" : lastInferenceError);
        return snapshot;
    }

    public synchronized boolean manualReload() {
        closeSession();
        return tryLoadModel();
    }

    private void markInference(double score, boolean success, String error) {
        lastInferenceTime = System.currentTimeMillis();
        lastInferenceSuccess = success;
        lastInferenceScore = score;
        lastInferenceError = error;
    }

    @PreDestroy
    public void destroy() {
        stopWatcher();
        closeSession();
    }

    private void closeSession() {
        synchronized (this) {
            try {
                if (session != null) {
                    session.close();
                }
            } catch (Exception ignored) {
                // ignored
            } finally {
                session = null;
                inputName = null;
                modelLoaded = false;
            }
        }
    }

    private void startWatcher() {
        try {
            Path model = Path.of(modelPath);
            Path dir = model.getParent();
            if (dir == null || !Files.exists(dir)) {
                return;
            }
            watchService = dir.getFileSystem().newWatchService();
            dir.register(watchService, StandardWatchEventKinds.ENTRY_MODIFY, StandardWatchEventKinds.ENTRY_CREATE);
            watcherRunning.set(true);
            watchThread = new Thread(this::watchLoop, "rf-model-watch");
            watchThread.setDaemon(true);
            watchThread.start();
        } catch (Exception e) {
            log.warn("[RF] 启动模型热更新监听失败: {}", e.getMessage());
        }
    }

    private void watchLoop() {
        Path modelName = Path.of(modelPath).getFileName();
        while (watcherRunning.get()) {
            try {
                WatchKey key = watchService.take();
                for (WatchEvent<?> event : key.pollEvents()) {
                    Path changed = (Path) event.context();
                    if (modelName != null && modelName.equals(changed)) {
                        log.info("[RF] 检测到模型文件变更，开始热重载: {}", changed);
                        manualReload();
                    }
                }
                key.reset();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                log.warn("[RF] 模型监听循环异常: {}", e.getMessage());
            }
        }
    }

    private void stopWatcher() {
        watcherRunning.set(false);
        if (watchThread != null) {
            watchThread.interrupt();
        }
        if (watchService != null) {
            try {
                watchService.close();
            } catch (Exception ignored) {
                // ignored
            }
        }
    }
}
