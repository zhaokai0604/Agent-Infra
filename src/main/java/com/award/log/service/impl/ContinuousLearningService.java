package com.award.log.service.impl;

import com.award.log.mapper.DecisionFeedbackMapper;
import com.award.log.model.DecisionFeedback;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class ContinuousLearningService {

    @Autowired
    private DecisionFeedbackMapper decisionFeedbackMapper;

    @Value("${log.pipeline.continuous-learning.sample-threshold:500}")
    private int sampleThreshold;

    @Value("${log.pipeline.continuous-learning.enabled:true}")
    private boolean enabled;

    private static final Path TRAIN_SCRIPT_PATH = Paths.get("scripts/ml/train_model.py");
    private static final Path TRAIN_DATA_DIR = Paths.get("logs/training");
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Scheduled(cron = "0 0 2 * * ?")
    public void dailyCollectAndRetrain() {
        runCollectAndRetrain(false);
    }

    /**
     * @param force 手动触发时忽略样本阈值与 enabled=false（仍会真实跑脚本）
     * @return 是否实际完成一次成功训练并标记样本
     */
    public boolean runCollectAndRetrain(boolean force) {
        if (!enabled && !force) {
            log.info("[持续学习] 持续学习功能已禁用");
            return false;
        }

        log.info("[持续学习] ===== 开始执行样本收集和训练任务 force={} =====", force);

        try {
            int untrainedCount = decisionFeedbackMapper.countUntrained();
            log.info("[持续学习] 当前未训练样本数: [{}]", untrainedCount);

            if (!force && untrainedCount < sampleThreshold) {
                log.info("[持续学习] 未训练样本数 [{}] 低于阈值 [{}]，跳过重训练", untrainedCount, sampleThreshold);
                return false;
            }
            if (untrainedCount <= 0) {
                log.warn("[持续学习] 无未训练样本，无法训练");
                return false;
            }

            List<DecisionFeedback> samples = decisionFeedbackMapper.selectUntrainedSamples(5000);
            log.info("[持续学习] 成功获取 [{}] 个训练样本", samples.size());

            Path trainingDataFile = exportTrainingData(samples);
            log.info("[持续学习] 训练数据已导出至: [{}]", trainingDataFile);

            boolean trainSuccess = runTrainScript(trainingDataFile);

            if (trainSuccess) {
                List<Long> trainedIds = samples.stream()
                        .map(DecisionFeedback::getId)
                        .filter(id -> id != null)
                        .toList();
                if (!trainedIds.isEmpty()) {
                    int marked = decisionFeedbackMapper.markAsTrained(trainedIds);
                    log.info("[持续学习] 已标记 [{}] 个样本为已训练", marked);
                }
                log.info("[持续学习] ===== 持续学习任务完成 =====");
                return true;
            }
            log.error("[持续学习] ===== 训练失败，请检查日志 =====");
            return false;
        } catch (Exception e) {
            log.error("[持续学习] 执行过程中发生异常", e);
            return false;
        }
    }

    private Path exportTrainingData(List<DecisionFeedback> samples) throws Exception {
        if (!Files.exists(TRAIN_DATA_DIR)) {
            Files.createDirectories(TRAIN_DATA_DIR);
        }

        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        Path outputFile = TRAIN_DATA_DIR.resolve("training_data_" + timestamp + ".jsonl");

        try (BufferedWriter writer = Files.newBufferedWriter(outputFile)) {
            for (DecisionFeedback sample : samples) {
                Map<String, Object> record = new LinkedHashMap<>();
                record.put("content", sample.getLogContent() != null ? sample.getLogContent() : "");
                record.put("label", sample.getActualAlert() != null ? sample.getActualAlert() : 0);
                record.put("level", sample.getLogLevel() != null ? sample.getLogLevel() : "INFO");
                record.put("template", sample.getLogTemplate() != null ? sample.getLogTemplate() : "");
                record.put("confidence", sample.getModelConfidence() != null ? sample.getModelConfidence() : 0.0);
                record.put("decisionId", sample.getDecisionId() != null ? sample.getDecisionId() : "");
                record.put("reviewer", sample.getReviewer() != null ? sample.getReviewer() : "");
                record.put("remark", sample.getRemark() != null ? sample.getRemark() : "");
                record.put("error_rate_1m", sample.getErrorRate1m() != null ? sample.getErrorRate1m() : 0.0);
                record.put("error_1m", sample.getError1m() != null ? sample.getError1m() : 0.0);
                record.put("total_1m", sample.getTotal1m() != null ? sample.getTotal1m() : 0.0);
                record.put("interval_ms", sample.getIntervalMs() != null ? sample.getIntervalMs() : 0.0);
                writer.write(OBJECT_MAPPER.writeValueAsString(record));
                writer.newLine();
            }
        }

        return outputFile;
    }

    private boolean runTrainScript(Path trainingDataFile) {
        log.info("[持续学习] 开始执行训练脚本...");

        try {
            if (!Files.exists(TRAIN_SCRIPT_PATH)) {
                log.error("[持续学习] 训练脚本不存在: [{}]", TRAIN_SCRIPT_PATH);
                return false;
            }

            List<String> command = new ArrayList<>();
            command.add("python");
            command.add(TRAIN_SCRIPT_PATH.toString());
            command.add("--training-data");
            command.add(trainingDataFile.toString());

            ProcessBuilder processBuilder = new ProcessBuilder(command);
            processBuilder.redirectErrorStream(true);
            Process process = processBuilder.start();

            try (BufferedReader br = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = br.readLine()) != null) {
                    log.info("[持续学习] [训练脚本] {}", line);
                }
            }

            boolean finished = process.waitFor(45, java.util.concurrent.TimeUnit.MINUTES);
            if (!finished) {
                process.destroyForcibly();
                log.error("[持续学习] 训练脚本超时（45 分钟），已强制终止");
                return false;
            }
            int exitCode = process.exitValue();
            if (exitCode == 0) {
                log.info("[持续学习] 训练脚本执行成功");
                return true;
            }
            log.error("[持续学习] 训练脚本执行失败，退出码: [{}]", exitCode);
            return false;
        } catch (Exception e) {
            log.error("[持续学习] 执行训练脚本时发生异常", e);
            return false;
        }
    }

    public int getUntrainedSampleCount() {
        try {
            return decisionFeedbackMapper.countUntrained();
        } catch (Exception e) {
            log.error("[持续学习] 获取未训练样本数失败", e);
            return 0;
        }
    }

    public boolean triggerManualTraining() {
        log.info("[持续学习] 收到手动训练触发请求");
        return runCollectAndRetrain(true);
    }
}
