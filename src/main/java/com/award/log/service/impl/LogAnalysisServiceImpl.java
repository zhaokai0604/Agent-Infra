package com.award.log.service.impl;

import com.award.log.analysis.AiDiagnosisContextBuilder;
import com.award.log.analysis.GeneralLogSeverityResolver;
import com.award.log.analysis.LinuxLogParseSupport;
import com.award.log.analysis.LogFieldPlaceholder;
import com.award.log.analysis.LogAnomalyHeuristics;
import com.award.log.analysis.LogLineParseSupport;
import com.award.log.dto.EnhancedLogParseResultEntity;
import com.award.log.analyzer.DrainParser;
import com.award.log.analyzer.DrainParserFactory;
import com.award.log.model.LogProtocolType;
import com.award.log.model.LogSeverityLevel;
import com.award.log.analyzer.LogCleaner;
import com.award.log.collector.model.RawLogEvent;
import com.award.log.decision.CollaborativeDecisionService;
import com.award.log.decision.DecisionInput;
import com.award.log.decision.DecisionResult;
import com.award.log.service.AiLogAlarmService;
import com.award.log.service.LogAnalysisService;
import com.award.log.task.AnalysisTaskManager;
import com.award.log.task.TaskInfo;
import com.award.log.util.LogAnalysisArtifactCleaner;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Service
public class LogAnalysisServiceImpl implements LogAnalysisService {

    /** 智能决策逐条评估上限，避免大文件在 89% 后长时间无进度 */
    private static final int ROUTER_EVAL_MAX = 150;
    /** 热循环内取消检查间隔（行），暂停/取消仅在边界处阻塞 */
    private static final int CANCEL_CHECK_EVERY_LINES = 256;
    private static final long ANALYSIS_QUEUE_WAIT_MS = 60_000L;

    @Value("${log.analysis.max-concurrent-tasks:4}")
    private int maxConcurrentAnalysisTasks;

    @Value("${log.analysis.max-lines:300000}")
    private int maxAnalysisLines;

    private Semaphore analysisConcurrencyGate;

    // 任务状态映射，用于跟踪每个任务的状态
    private final ConcurrentHashMap<String, String> taskStatusMap = new ConcurrentHashMap<>();

    // 任务断点信息映射，用于实现断点续传
    private final ConcurrentHashMap<String, Map<String, Object>> taskBreakpointMap = new ConcurrentHashMap<>();
    
    // 任务线程映射，用于跟踪每个任务的执行线程，以便取消任务时中断线程
    private final ConcurrentHashMap<String, Thread> taskThreadMap = new ConcurrentHashMap<>();
    
    // 已分析文件集合，用于确保每个文件只分析一次
    private final Set<String> analyzedFiles = ConcurrentHashMap.newKeySet();

    @Autowired
    private AnalysisTaskManager taskManager;

    @Autowired
    private LogAnalysisArtifactCleaner artifactCleaner;

    @Autowired
    @Qualifier("logAnalysisExecutor")
    private ThreadPoolTaskExecutor logAnalysisExecutor;

    @Autowired
    @Qualifier("logAnalysisShardExecutor")
    private ThreadPoolTaskExecutor logAnalysisShardExecutor;
    
    @Autowired
    private AiLogAlarmService aiLogAlarmService;
    
    @Autowired
    private LogCleaner logCleaner;
    
    @Autowired
    private CollaborativeDecisionService collaborativeDecisionService;

    /** 实时 / 批量 / MCP 共用同一 Drain-Plus（PLUS）Bean，模板 ID 全局一致 */
    @Autowired
    private DrainParserFactory drainParserFactory;

    @PostConstruct
    void initAnalysisConcurrencyGate() {
        analysisConcurrencyGate = new Semaphore(Math.max(1, maxConcurrentAnalysisTasks), true);
        log.info("日志分析并发上限: {}", Math.max(1, maxConcurrentAnalysisTasks));
    }

    // 暂停任务：阻塞分析线程直至恢复或取消
    @Override
    public void pauseAnalysis(String taskId) {
        if (taskManager.isTerminalDbStatus(taskId)) {
            throw new IllegalStateException("任务已结束，无法暂停");
        }
        log.info("[任务状态变更] 暂停任务，任务ID: [{}]", taskId);
        taskStatusMap.put(taskId, "PAUSED");
        taskManager.pauseTask(taskId);
    }

    @Override
    public void resumeAnalysis(String taskId) {
        String mem = taskStatusMap.get(taskId);
        String db = taskManager.getDbStatus(taskId);
        if (!"PAUSED".equals(mem) && !"PAUSED".equals(db)) {
            throw new IllegalStateException("仅已暂停的任务可恢复；当前状态: mem=" + mem + ", db=" + db);
        }
        log.info("[任务状态变更] 恢复任务，任务ID: [{}]", taskId);
        taskStatusMap.put(taskId, "PROCESSING");
        taskManager.resumeTask(taskId);
    }
    
    // 添加取消任务方法
    @Override
    public void cancelAnalysis(String taskId) {
        if (taskManager.isTerminalDbStatus(taskId)) {
            throw new IllegalStateException("任务已结束（完成/失败/已取消），无法再次取消");
        }
        log.info("[任务状态变更] 开始取消任务，任务ID: [{}]", taskId);
        
        String oldStatus = taskStatusMap.get(taskId);
        taskStatusMap.put(taskId, "CANCELLED");
        log.debug("[任务状态变更] 内存状态已更新，任务ID: [{}]，从 [{}] 变为 [CANCELLED]", taskId, oldStatus);
        
        taskManager.cancelTask(taskId, "任务被用户取消");
        artifactCleaner.purgeTaskArtifacts(taskId);

        Thread taskThread = taskThreadMap.remove(taskId);
        if (taskThread != null) {
            taskThread.interrupt();
            log.info("[任务状态变更] 已中断任务线程: [{}]，任务ID: [{}]", taskThread.getName(), taskId);
        } else {
            log.debug("[任务状态变更] 未找到任务执行线程，任务ID: [{}]", taskId);
        }
        
        log.info("[任务状态变更] 任务取消完成，任务ID: [{}]", taskId);
    }

    /**
     * 与 {@link LogAnalysisController#downloadReport} 使用相同目录约定，便于在 target 被清理或进程 cwd 变化后仍能导出。
     */
    @Override
    public void ensureReportArtifacts(String taskId, List<EnhancedLogParseResultEntity> results) throws IOException {
        if (taskId == null || taskId.isBlank() || results == null || results.isEmpty()) {
            return;
        }
        EngineConfig config = new EngineConfig();
        config.outputDir = System.getProperty("user.dir") + File.separator + "target" + File.separator + "output"
                + File.separator + taskId;
        exportCsvResult(results, config);
        generateHtmlReport(results, config);
    }

    @Async("logAnalysisExecutor")
    @Override
    public void startAnalysisAsync(File logFile, String taskId) {
        String filePath = logFile.getAbsolutePath();
        String dedupKey = buildFileDedupKey(logFile);
        if (analyzedFiles.contains(dedupKey)) {
            log.warn("【文件跳过】文件 {} 内容与本次运行中已完成任务相同，拒绝重复分析", filePath);
            taskStatusMap.put(taskId, "FAILED");
            taskManager.failTask(taskId, "该文件内容与本次运行中已分析的任务相同，请修改文件或重新上传后再试");
            artifactCleaner.purgeTaskArtifacts(taskId);
            return;
        }

        boolean acquired = false;
        try {
            acquired = analysisConcurrencyGate.tryAcquire(ANALYSIS_QUEUE_WAIT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (!acquired) {
            log.warn("【分析排队超时】任务 {} 等待并发槽位超过 {} ms", taskId, ANALYSIS_QUEUE_WAIT_MS);
            taskStatusMap.put(taskId, "FAILED");
            taskManager.failTask(taskId, "当前分析任务较多，请稍后重试");
            artifactCleaner.purgeTaskArtifacts(taskId);
            try {
                Files.deleteIfExists(logFile.toPath());
            } catch (Exception ignored) {
            }
            return;
        }

        analyzedFiles.add(dedupKey);
        
        // 初始化任务状态
        taskStatusMap.put(taskId, "PROCESSING");
        
        // 记录当前线程，以便取消任务时中断线程
        Thread currentThread = Thread.currentThread();
        taskThreadMap.put(taskId, currentThread);
        
            long startTime = System.currentTimeMillis();

            EngineConfig config = new EngineConfig();
            // 每个任务独立输出目录，避免并发/多任务时固定文件名互相覆盖导致下载错报告
            config.outputDir = config.outputDir + File.separator + taskId;

            taskManager.updateProgress(taskId, 5, "引擎初始化完成，开始读取文件...");

            

            try (InputStream inputStream = new FileInputStream(logFile)) {

                int adjustedShardSize = MemoryMonitor.adjustShardSize(config.shardSize);
                int lineCap = Math.max(10_000, maxAnalysisLines);
                LogShardUtil.SplitOutcome splitOutcome =
                        LogShardUtil.splitLogToShards(inputStream, adjustedShardSize, lineCap);
                List<List<String>> logShards = splitOutcome.shards();

                if (logShards.isEmpty()) {

                    taskManager.failTask(taskId, "文件内容为空，未读取到日志数据。");
                    taskStatusMap.put(taskId, "FAILED");
                    artifactCleaner.purgeTaskArtifacts(taskId);
                    return;

                }

                if (splitOutcome.truncated()) {
                    taskManager.updateProgress(taskId, 8,
                            "文件过大，已截断至 " + lineCap + " 行（跳过 " + splitOutcome.skippedLines() + " 行）...");
                    log.warn("【分析行数上限】taskId={} maxLines={} skipped={}",
                            taskId, lineCap, splitOutcome.skippedLines());
                }

                taskManager.updateProgress(taskId, 10, "文件分片完成，准备并行处理...");

                log.info("【日志分片完成】总分片数: [{}], 实际分片大小: [{}], 已读行数: [{}], 截断: [{}]",
                        logShards.size(), adjustedShardSize, splitOutcome.readLines(), splitOutcome.truncated());

    

                // 2. Drain-Plus（与 MCP、实时分析同源单例）
                DrainParser drainParser = drainParserFactory.getPlusParser();

                ConcurrentHashMap<Integer, List<EnhancedLogParseResultEntity>> shardResults = new ConcurrentHashMap<>();

    

                // 3. 线程池处理

                log.info("【多线程处理】使用注入的任务线程池进行分析...");

    

                CountDownLatch countDownLatch = new CountDownLatch(logShards.size());

                AtomicInteger processedShards = new AtomicInteger(0);
                AtomicInteger failedShards = new AtomicInteger(0);

    

                for (int i = 0; i < logShards.size(); i++) {

                    final int shardIndex = i;

                    final List<String> shardLines = logShards.get(i);

                    logAnalysisShardExecutor.execute(() -> {

                        try {

                            log.debug("【分片处理中】正在处理第 [{}/{}] 个分片，包含 [{}] 行日志", shardIndex + 1, logShards.size(), shardLines.size());

                            int currentBatchSize = MemoryMonitor.adjustBatchSize(config.batchSize, config.minBatchSize, config.memoryWarnThreshold);

                            List<EnhancedLogParseResultEntity> shardResult = processLogBatch(shardLines, drainParser, config, currentBatchSize, taskId);

                            shardResults.put(shardIndex, shardResult);

                            

                            // 更新任务进度 (10% - 80%)

                            int done = processedShards.incrementAndGet();

                            int progress = 10 + (int)((done / (double)logShards.size()) * 70);

                            taskManager.updateProgress(taskId, progress, "正在并行分析分片 " + done + "/" + logShards.size());

                            

                        } catch (Exception e) {

                            log.error("【分片处理异常】分片索引 [" + shardIndex + "]", e);
                            failedShards.incrementAndGet();

                        } finally {

                            countDownLatch.countDown();

                        }

                    });

                }

    

                List<EnhancedLogParseResultEntity> finalResult;
                try {

                    countDownLatch.await();

                    // 检查任务是否被取消，如果被取消则直接退出
                    if (Thread.currentThread().isInterrupted() || "CANCELLED".equals(taskStatusMap.get(taskId))) {
                        log.info("【任务取消】多线程处理被中断，任务ID: [{}]", taskId);
                        throw new InterruptedException("任务被用户取消");
                    }

                    if (failedShards.get() > 0 || shardResults.size() != logShards.size()) {
                        String err = String.format("分片分析失败 %d/%d，结果不完整，已终止任务",
                                failedShards.get(), logShards.size());
                        log.error("【分片完整性校验失败】任务ID: [{}] {}", taskId, err);
                        taskManager.failTask(taskId, err);
                        taskStatusMap.put(taskId, "FAILED");
                        return;
                    }

                    finalResult = new ArrayList<>();
                    for (int si = 0; si < logShards.size(); si++) {
                        List<EnhancedLogParseResultEntity> part = shardResults.get(si);
                        if (part != null) {
                            finalResult.addAll(part);
                        }
                    }
                    for (int li = 0; li < finalResult.size(); li++) {
                        finalResult.get(li).setSourceLineIndex(li);
                    }

                    log.info("【多线程处理结束】累计解析条目: [{}]", finalResult.size());

                } catch (InterruptedException e) {

                    log.error("分析线程被打断，任务ID: [{}]", taskId, e);

                    Thread.currentThread().interrupt();
                    // 当线程被中断时，直接抛出异常，终止后续操作
                    throw new RuntimeException("任务被中断", e);

                }

                // 检查任务是否被暂停或取消
                checkCancelStatus(taskId);
                // 检查任务是否被取消，如果被取消则直接退出
                if (Thread.currentThread().isInterrupted() || "CANCELLED".equals(taskStatusMap.get(taskId))) {
                    log.info("【任务取消】检测到中断状态，任务ID: [{}]", taskId);
                    throw new RuntimeException("任务被用户取消");
                }
                
                taskManager.updateProgress(taskId, 80, "基础分析完成，开始多维度异常检测...");

            // 4. 异常检测 (完整逻辑还原)
            if (config.enableAnomalyDetect) {
                // 检查任务是否被暂停或取消
                checkCancelStatus(taskId);
                log.info("【异常检测启动】正在进行多维度异常分析...");
                detectTemporalAnomaly(finalResult, config, taskId);
                
                // 检查任务是否被暂停或取消
                checkCancelStatus(taskId);
                // 检查任务是否被取消
                if (Thread.currentThread().isInterrupted() || "CANCELLED".equals(taskStatusMap.get(taskId))) {
                    log.info("【任务取消】异常检测被中断，任务ID: [{}]", taskId);
                    throw new RuntimeException("任务被用户取消");
                }
                
                taskManager.updateProgress(taskId, 83, "时序异常分析完成...");
                
                detectKeywordAnomaly(finalResult, taskId);
                
                // 检查任务是否被暂停或取消
                checkCancelStatus(taskId);
                // 检查任务是否被取消
                if (Thread.currentThread().isInterrupted() || "CANCELLED".equals(taskStatusMap.get(taskId))) {
                    log.info("【任务取消】关键词检测被中断，任务ID: [{}]", taskId);
                    throw new RuntimeException("任务被用户取消");
                }
                
                taskManager.updateProgress(taskId, 86, "关键词风险扫描完成...");
                
                detectFrequencyAnomaly(finalResult, config, taskId);
                
                // 检查任务是否被取消
                if (Thread.currentThread().isInterrupted() || "CANCELLED".equals(taskStatusMap.get(taskId))) {
                    log.info("【任务取消】频次检测被中断，任务ID: [{}]", taskId);
                    throw new RuntimeException("任务被用户取消");
                }
                
                taskManager.updateProgress(taskId, 89, "频次统计异常分析完成...");
            
            calcComprehensiveAnomalyScore(finalResult, taskId);
                
                // 检查任务是否被取消
                if (Thread.currentThread().isInterrupted() || "CANCELLED".equals(taskStatusMap.get(taskId))) {
                    log.info("【任务取消】综合评分被中断，任务ID: [{}]", taskId);
                    throw new RuntimeException("任务被用户取消");
                }
                
                // 集成 IntelligentRouter 进行决策评估
                log.info("【智能决策】开始使用 IntelligentRouter 评估异常日志...");
                evaluateAnomaliesWithIntelligentRouter(finalResult, taskId);
                
                long anomalyCount = finalResult.stream().filter(EnhancedLogParseResultEntity::isAnomaly).count();
                log.info("【异常检测完成】共发现 [{}] 条异常日志。", anomalyCount);
            }
            
            // 检查任务是否被取消
            if (Thread.currentThread().isInterrupted() || "CANCELLED".equals(taskStatusMap.get(taskId))) {
                log.info("【任务取消】异常检测完成后被中断，任务ID: [{}]", taskId);
                throw new RuntimeException("任务被用户取消");
            }
            
            taskManager.updateProgress(taskId, 92, "异常检测完成，正在写入结果...");

            // 5. 先完成任务入库，缩短轮询等待；CSV/HTML 报告异步生成
            long endTime = System.currentTimeMillis();
            long costTime = endTime - startTime;
            log.info("【分析任务完成】总耗时: [{} 毫秒].", costTime);
            
            long anomalyCount = finalResult.stream().filter(EnhancedLogParseResultEntity::isAnomaly).count();
            TaskInfo.TaskSummary summary = new TaskInfo.TaskSummary();
            summary.setTotalLogs(finalResult.size());
            summary.setAnomalyCount((int)anomalyCount);
            summary.setAnomalyRate(finalResult.isEmpty() ? 0 : (double)anomalyCount / finalResult.size());
            summary.setCostTime(costTime);
            if (splitOutcome.truncated()) {
                summary.setLineCapApplied(true);
                summary.setLinesSkipped(splitOutcome.skippedLines());
            }

            final boolean exportCsv = config.enableCsvExport;
            final boolean exportHtml = config.enableVisualReport;
            final String exportOutputDir = config.outputDir;
            // 不双份拷贝全量结果；导出异步任务直接复用 finalResult（完成后由引用释放）

            // 6. AI告警处理：文件级别整体告警（最多采样 40 条，避免超大 Prompt）
            if (config.enableAnomalyDetect && anomalyCount > 0) {
                log.info("【AI告警启动】开始处理异常日志，生成智能告警...");
                log.info("【AI告警启动】异常日志数量: [{}]", anomalyCount);

                final int alarmSampleLimit = 40;
                try {
                    logAnalysisExecutor.execute(() -> {
                    log.info("【AI告警线程】开始执行告警处理，任务ID: [{}]", taskId);
                    try {
                        List<String> anomalyReasons = new ArrayList<>();
                        StringBuilder anomalyLogsSummary = new StringBuilder();
                        int sampled = 0;

                        for (EnhancedLogParseResultEntity anomalyLog : finalResult) {
                            if (!anomalyLog.isAnomaly()) {
                                continue;
                            }
                            anomalyReasons.addAll(anomalyLog.getAnomalyReasons());
                            if (sampled < alarmSampleLimit) {
                                anomalyLogsSummary.append("[时间: ").append(anomalyLog.getLogTime())
                                        .append("][等级: ").append(anomalyLog.getSeverity().getSeverityCnName())
                                        .append("][原因: ").append(String.join("| ", anomalyLog.getAnomalyReasons()))
                                        .append("][内容: ").append(AiDiagnosisContextBuilder.truncateForPrompt(
                                                anomalyLog.getDesensitizedLog(), AiDiagnosisContextBuilder.ALARM_LOG_SNIPPET_CHARS))
                                        .append("\n");
                                sampled++;
                            }
                        }

                        Set<String> uniqueReasons = new HashSet<>(anomalyReasons);

                        StringBuilder alarmContent = new StringBuilder();
                        alarmContent.append("【文件异常分析报告】\n")
                                .append("文件路径: " + logFile.getAbsolutePath()).append("\n")
                                .append("总日志行数: " + finalResult.size()).append("\n")
                                .append("异常日志数量: " + anomalyCount).append("\n")
                                .append("异常率: " + String.format("%.2f%%", (double) anomalyCount / finalResult.size() * 100)).append("\n")
                                .append("\n异常原因汇总: \n");

                        for (String reason : uniqueReasons) {
                            alarmContent.append("- " + reason).append("\n");
                        }

                        alarmContent.append("\n异常日志示例（最多 ").append(alarmSampleLimit).append(" 条）: \n")
                                .append(anomalyLogsSummary);

                        aiLogAlarmService.analyzeLogAndGenerateAlarm(
                            taskId,
                            alarmContent.toString()
                        );

                        log.info("【AI告警线程】完成文件级别整体告警处理，任务ID: [{}]", taskId);
                    } catch (Exception e) {
                        log.error("【AI告警异常】处理文件整体告警失败，异常信息: [{}]", e.getMessage(), e);
                    }
                });
                } catch (java.util.concurrent.RejectedExecutionException rex) {
                    log.warn("【AI告警】线程池繁忙，跳过告警提交（不影响分析完成）: {}", rex.getMessage());
                }
            } else {
                log.info("【AI告警跳过】未满足告警条件，enableAnomalyDetect: [{}], anomalyCount: [{}]",
                        config.enableAnomalyDetect, anomalyCount);
            }

            taskManager.completeTask(taskId, finalResult, summary);
            taskStatusMap.put(taskId, "COMPLETED");

            if (exportCsv || exportHtml) {
                try {
                    logAnalysisExecutor.execute(() ->
                            exportReportArtifactsAsync(taskId, finalResult, exportOutputDir, exportCsv, exportHtml));
                } catch (java.util.concurrent.RejectedExecutionException rex) {
                    log.warn("【报告导出】线程池繁忙，将同步生成报告产物: {}", rex.getMessage());
                    try {
                        exportReportArtifactsAsync(taskId, finalResult, exportOutputDir, exportCsv, exportHtml);
                    } catch (Exception ex) {
                        log.error("【报告导出】同步生成失败（分析本身已完成）: {}", ex.getMessage());
                    }
                }
            }

            // 成功：仅删除上传临时文件，保留 output 供下载
            artifactCleaner.deleteUploadTemps(taskId);

        } catch (Exception e) {
            String memStatus = taskStatusMap.get(taskId);
            String dbStatus = taskManager.getDbStatus(taskId);
            // 终态任务（完成/取消/已失败）不降级、不清理产物
            if ("CANCELLED".equals(memStatus) || "CANCELLED".equals(dbStatus)
                    || "COMPLETED".equals(dbStatus) || "FAILED".equals(dbStatus)
                    || taskManager.isTerminalDbStatus(taskId)) {
                if ("COMPLETED".equals(dbStatus)) {
                    log.warn("分析后续步骤异常但任务已完成，保留 COMPLETED: {} - {}", taskId, e.getMessage());
                } else {
                    log.info("任务 [{}] 已终态({}/{})，不再降级处理: {}", taskId, memStatus, dbStatus, e.getMessage());
                }
            } else {
                log.error("分析任务失败", e);
                String msg = e instanceof java.util.concurrent.RejectedExecutionException
                        ? "分析队列繁忙，请稍后重试"
                        : ("分析过程发生意外错误: " + e.getMessage());
                taskManager.failTask(taskId, msg);
                taskStatusMap.put(taskId, "FAILED");
                artifactCleaner.purgeTaskArtifacts(taskId);
            }
        } finally {
            if (acquired) {
                analysisConcurrencyGate.release();
            }
            taskThreadMap.remove(taskId);
            String terminal = taskStatusMap.get(taskId);
            if ("COMPLETED".equals(terminal) || "FAILED".equals(terminal) || "CANCELLED".equals(terminal)) {
                taskStatusMap.remove(taskId);
            }
            if (logFile != null) {
                try {
                    Files.deleteIfExists(logFile.toPath());
                } catch (Exception ignored) {
                }
            }
        }
    }

    private void exportReportArtifactsAsync(String taskId,
                                            List<EnhancedLogParseResultEntity> results,
                                            String outputDir,
                                            boolean exportCsv,
                                            boolean exportHtml) {
        EngineConfig config = new EngineConfig();
        config.outputDir = outputDir;
        config.enableCsvExport = exportCsv;
        config.enableVisualReport = exportHtml;
        try {
            if (exportCsv) {
                exportCsvResult(results, config);
            }
            if (exportHtml) {
                generateHtmlReport(results, config);
            }
            log.info("【报告导出完成】任务ID: [{}]", taskId);
        } catch (Exception e) {
            log.warn("【报告导出失败】任务ID: [{}], {}", taskId, e.getMessage());
        }
    }

    // --- 业务逻辑方法 (从静态方法重构为实例方法或私有辅助方法) ---

    private List<EnhancedLogParseResultEntity> processLogBatch(
            List<String> batchLines,
            DrainParser drainParser,
            EngineConfig config,
            int currentBatchSize,
            String taskId) {
        List<EnhancedLogParseResultEntity> result = new ArrayList<>();
        List<String> tempLines = new ArrayList<>(currentBatchSize);
        int processedInShard = 0;
        int lineIndex = 0;

        for (String line : batchLines) {
            if (lineIndex++ % CANCEL_CHECK_EVERY_LINES == 0) {
                checkCancelStatus(taskId);
            } else {
                checkCancelledOnly(taskId);
            }
            
            // 检查当前线程是否被中断
            if (Thread.currentThread().isInterrupted()) {
                log.info("任务 [{}] 执行线程被中断，退出处理", taskId);
                Thread.currentThread().interrupt();
                break;
            }
            
            tempLines.add(line);
            if (tempLines.size() >= currentBatchSize) {
                result.addAll(processLines(tempLines, drainParser, config));
                processedInShard += tempLines.size();
                tempLines.clear();
            }
        }
        
        // 检查任务是否被暂停或取消
        checkCancelStatus(taskId);
        
        // 检查当前线程是否被中断
        if (Thread.currentThread().isInterrupted()) {
            log.info("任务 [{}] 执行线程被中断，退出处理", taskId);
            Thread.currentThread().interrupt();
            return result;
        }
        
        if (!tempLines.isEmpty()) {
            processedInShard += tempLines.size();
            result.addAll(processLines(tempLines, drainParser, config));
        }
        return result;
    }
    
    /**
     * 检查任务是否被取消；暂停时在边界处阻塞等待恢复。
     */
    private void checkCancelStatus(String taskId) {
        checkCancelledOnly(taskId);
        waitWhilePaused(taskId);
    }

    private void checkCancelledOnly(String taskId) {
        if ("CANCELLED".equals(taskStatusMap.get(taskId))) {
            log.info("任务 [{}] 已被取消，退出执行", taskId);
            Thread.currentThread().interrupt();
        }
    }

    private void waitWhilePaused(String taskId) {
        while ("PAUSED".equals(taskStatusMap.get(taskId))) {
            if ("CANCELLED".equals(taskStatusMap.get(taskId))) {
                log.info("任务 [{}] 暂停期间被取消", taskId);
                Thread.currentThread().interrupt();
                return;
            }
            try {
                Thread.sleep(400);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("任务被中断", e);
            }
        }
    }

    private String buildFileDedupKey(File logFile) {
        try {
            return logFile.getAbsolutePath() + "|"
                    + Files.getLastModifiedTime(logFile.toPath()).toMillis() + "|"
                    + logFile.length();
        } catch (IOException e) {
            return logFile.getAbsolutePath() + "|" + logFile.length();
        }
    }

    private List<EnhancedLogParseResultEntity> processLines(List<String> lines, DrainParser drainParser, EngineConfig config) {
        return lines.stream().map(l -> {
            // 特殊处理 Windows 事件日志格式
            // 格式样本: 信息 2026/1/6 12:56:23 Service Control Manager 7040 ...
            String processedLine = l.trim();
            // 清洗日志内容（使用默认清洗规则）
            processedLine = logCleaner.cleanLog(processedLine, com.award.log.analyzer.DefaultCleanRules.getDefaultRules());
            LogSeverityLevel severity = GeneralLogSeverityResolver.resolve(processedLine);
            LogProtocolType protocol = LogLineParseSupport.detectProtocol(processedLine);
            
            // 针对不同协议的特殊提取逻辑（含 Linux syslog / journal / Apache 等）
            String timeStr = LogLineParseSupport.extractNormalizedTimeString(processedLine);
            if (timeStr == null || timeStr.isBlank()) {
                timeStr = LogFieldPlaceholder.TIME_UNPARSED;
            }
            long timestamp = LogFieldPlaceholder.TIME_UNPARSED.equals(timeStr)
                    ? 0L
                    : LogLineParseSupport.toEpochMillis(timeStr);
            
            // 尝试提取 Windows 事件 ID
            String eventId = "N/A";
            if (protocol == LogProtocolType.WINDOWS_EVENT_LOG) {
                // 简单的正则尝试提取 ID (通常是第4列附近的数字，或者是 '事件 ID xxx')
                Matcher idMatcher = Pattern.compile("(?i)事件\\s*ID\\s*[:\\s]\\s*(\\d+)").matcher(processedLine);
                if (idMatcher.find()) {
                    eventId = idMatcher.group(1);
                } else {
                     // 尝试基于位置提取 (假设是制表符分隔)
                     String[] parts = processedLine.split("\\s{2,}+|\t");
                     if (parts.length > 3 && parts[3].matches("\\d+")) {
                         eventId = parts[3];
                     }
                }
            }

            String desensitized = config.enableDesensitize ? desensitizeLog(processedLine) : processedLine;
            EnhancedLogParseResultEntity res = new EnhancedLogParseResultEntity(desensitized);
            
            res.setProtocol(protocol);
            res.setSeverity(severity);
            res.setTemplateId(drainParser.parse(processedLine));
            res.setLogTime(timeStr);
            res.setTimestamp(timestamp);
            if (protocol == LogProtocolType.WINDOWS_EVENT_LOG) {
                res.setPid(eventId);
            } else {
                String tagPid = LinuxLogParseSupport.extractTagPid(processedLine);
                if (tagPid != null) {
                    res.setPid(tagPid);
                } else {
                    String extPid = extractPid(processedLine);
                    res.setPid("未提取".equals(extPid) ? "N/A" : extPid);
                }
            }
            res.setUid(extractUid(processedLine));
            res.setTraceId(extractTraceId(processedLine));
            res.setStackTrace(extractStackTrace(processedLine));

            LinuxLogParseSupport.enrichContainerHints(res.getFeatureMap(), processedLine);
            if (protocol == LogProtocolType.LINUX_SYSTEM_LOG) {
                LinuxLogParseSupport.enrichFeatureMap(res.getFeatureMap(), processedLine, protocol);
            }
            
            // 仅 Windows 事件风格日志追加处置提示，避免应用 ERROR 行被误标成「驱动」类根因
            if ((severity == LogSeverityLevel.ERROR_LEVEL || severity == LogSeverityLevel.FATAL_LEVEL)
                    && protocol == LogProtocolType.WINDOWS_EVENT_LOG) {
                res.getAnomalyReasons().add("Windows 事件：建议结合事件 ID / 来源组件排查驱动或系统变更");
            }
            
            return res;
        }).collect(Collectors.toList());
    }

    // --- 脱敏所需的静态正则模式 ---
    private static final Pattern IP_PATTERN = Pattern.compile("(\\d{1,3}\\.\\d{1,3})(\\.\\d{1,3}\\.\\d{1,3})");
    private static final Pattern PHONE_PATTERN = Pattern.compile("(1[3-9]\\d)\\d{4}(\\d{4})");
    private static final Pattern EMAIL_PATTERN = Pattern.compile("(\\w)\\w{2,}(\\w)@(\\w+\\.\\w+)");
    private static final Pattern ACCOUNT_PATTERN = Pattern.compile("([a-zA-Z0-9])[a-zA-Z0-9_]{2,18}([a-zA-Z0-9])");

    // --- 辅助方法 (功能还原) ---

    /**
     * 对日志内容进行脱敏处理
     */
    private String desensitizeLog(String rawLog) {
        if (rawLog == null || rawLog.trim().isEmpty()) return "";
        String desensitized = rawLog;

        // 1. 分级IP脱敏 - JDK8 兼容
        Matcher ipMatcher = IP_PATTERN.matcher(desensitized);
        StringBuffer ipSb = new StringBuffer();
        while (ipMatcher.find()) {
            String ipPrefix = ipMatcher.group(1);
            String replacement;
            if (ipPrefix.startsWith("192.168") || ipPrefix.startsWith("10.") ||
                    (ipPrefix.startsWith("172.") && Integer.parseInt(ipPrefix.split("\\.")[1]) >= 16 && Integer.parseInt(ipPrefix.split("\\.")[1]) <= 31)) {
                replacement = ipPrefix + ".*.*";
            } else {
                replacement = ipPrefix.split("\\.")[0] + ".*.*.*";
            }
            ipMatcher.appendReplacement(ipSb, replacement);
        }
        ipMatcher.appendTail(ipSb);
        desensitized = ipSb.toString();

        // 2. 手机号脱敏
        desensitized = PHONE_PATTERN.matcher(desensitized).replaceAll("$1****$2");
        // 3. 邮箱脱敏
        desensitized = EMAIL_PATTERN.matcher(desensitized).replaceAll("$1***$2@$3");
        // 4. 账号脱敏
        desensitized = ACCOUNT_PATTERN.matcher(desensitized).replaceAll("$1***$2");

        return desensitized;
    }

    /**
     * 时序异常检测：基于特征向量的距离分析与时间窗口分析
     */
    private void detectTemporalAnomaly(List<EnhancedLogParseResultEntity> resultList, EngineConfig config, String taskId) {
        if (resultList.size() < 5) return;
        
        // 检查任务是否被暂停或取消
        checkCancelStatus(taskId);
        
        // 1. 特征编码
        Map<String, Integer> tempCodeMap = new HashMap<>();
        int codeId = 0;
        List<double[]> featureList = new ArrayList<>();
        for (int i = 0; i < resultList.size(); i++) {
            if (i % CANCEL_CHECK_EVERY_LINES == 0) {
                checkCancelStatus(taskId);
            } else {
                checkCancelledOnly(taskId);
            }
            EnhancedLogParseResultEntity res = resultList.get(i);
            tempCodeMap.putIfAbsent(res.getTemplateId(), codeId++);
            double[] feature = {
                    res.getTimestamp() / 1000.0,
                    tempCodeMap.get(res.getTemplateId()),
                    res.getSeverity().getSeverityScore(),
                    res.getProtocol().getProtocolCode(),
                    res.getRiskWeightScore()
            };
            featureList.add(feature);
        }
        
        // 2. 全局特征中心分析
        double[] meanVec = new double[5];
        featureList.forEach(vec -> {
            for (int i = 0; i < 5; i++) meanVec[i] += vec[i];
        });
        Arrays.setAll(meanVec, i -> meanVec[i] / featureList.size());
        
        // 3. 时间窗口分析 (窗口大小为10)
        int windowSize = 10;
        List<Double> windowScores = new ArrayList<>();
        
        for (int i = 0; i < resultList.size(); i++) {
            if (i % CANCEL_CHECK_EVERY_LINES == 0) {
                checkCancelStatus(taskId);
            } else {
                checkCancelledOnly(taskId);
            }

            EnhancedLogParseResultEntity res = resultList.get(i);
            double[] vec = featureList.get(i);
            
            // 计算全局距离
            double globalDist = 0.0;
            for (int j = 0; j < 5; j++) globalDist += Math.pow(vec[j] - meanVec[j], 2);
            globalDist = Math.sqrt(globalDist);
            
            // 计算时间窗口内的局部距离
            double localDist = 0.0;
            int start = Math.max(0, i - windowSize + 1);
            int end = Math.min(resultList.size(), i + 1);
            
            if (end - start >= 3) {
                double[] localMean = new double[5];
                for (int k = start; k < end; k++) {
                    double[] localVec = featureList.get(k);
                    for (int j = 0; j < 5; j++) localMean[j] += localVec[j];
                }
                Arrays.setAll(localMean, j -> localMean[j] / (end - start));
                
                for (int j = 0; j < 5; j++) localDist += Math.pow(vec[j] - localMean[j], 2);
                localDist = Math.sqrt(localDist);
            }
            
            // 综合全局和局部距离
            double combinedDist = globalDist * 0.6 + (localDist > 0 ? localDist * 0.4 : globalDist * 0.4);
            double score = Math.min(1.0, combinedDist / 100.0);
            
            // 添加时间窗口得分
            windowScores.add(score);
            
            // 计算移动平均得分
            if (i >= windowSize - 1) {
                double windowAvg = windowScores.subList(i - windowSize + 1, i + 1).stream().mapToDouble(Double::doubleValue).average().orElse(0);
                double scoreDeviation = Math.abs(score - windowAvg);
                
                // 如果当前得分与窗口平均得分差异过大，增加异常得分
                if (scoreDeviation > 0.3) {
                    score = Math.min(1.0, score + scoreDeviation * 0.5);
                }
            }
            
            res.setAnomalyScore(score);
            if (score > config.anomalyThreshold) {
                res.setAnomaly(true);
                res.getAnomalyReasons().add("时序聚类异常：距离特征中心过远（加权得分）");
            }
        }
    }

    /**
     * 关键词异常检测：匹配高危风险词汇，支持权重机制
     */
    private void detectKeywordAnomaly(List<EnhancedLogParseResultEntity> resultList, String taskId) {
        checkCancelStatus(taskId);
        LogAnomalyHeuristics.applyKeywordDetection(resultList);
    }

    /**
     * 频次异常检测：针对罕见模板或突发频率进行分析，支持时间窗口分析
     */
    private void detectFrequencyAnomaly(List<EnhancedLogParseResultEntity> resultList, EngineConfig config, String taskId) {
        checkCancelStatus(taskId);
        Objects.requireNonNull(config, "engine config");
        LogAnomalyHeuristics.applyFrequencyDetection(resultList);
    }

    /**
     * 计算综合异常得分：整合多维度检测结果，考虑时间上下文和严重程度
     */
    private void calcComprehensiveAnomalyScore(List<EnhancedLogParseResultEntity> resultList, String taskId) {
        checkCancelStatus(taskId);
        taskManager.updateProgress(taskId, 89, "正在计算综合异常得分...");
        LogAnomalyHeuristics.calcComprehensiveAnomalyScore(resultList);
        taskManager.updateProgress(taskId, 90, "综合异常评分完成...");
    }

    /**
     * 提取进程 ID
     */
    private String extractPid(String logLine) {
        Matcher m1 = Pattern.compile("PID[:=]\\s*(\\d+)").matcher(logLine);
        if (m1.find()) return m1.group(1);
        Matcher m2 = Pattern.compile("\\[(\\d+)\\]").matcher(logLine);
        if (m2.find()) return m2.group(1);
        return "未提取";
    }

    /**
     * 提取用户 ID
     */
    private String extractUid(String logLine) {
        Matcher m1 = Pattern.compile("UID[:=]\\s*(\\d+)").matcher(logLine);
        if (m1.find()) return m1.group(1);
        Matcher m2 = Pattern.compile("user[:=]\\s*(\\w+)").matcher(logLine);
        if (m2.find()) return m2.group(1);
        return "未提取";
    }

    /**
     * 提取调用链 ID (Trace ID)
     */
    private String extractTraceId(String logLine) {
        Matcher m = Pattern.compile("trace[id_]{0,2}[:=]\\s*(\\w+)").matcher(logLine.toLowerCase());
        return m.find() ? m.group(1) : "未提取";
    }

    /**
     * 提取异常堆栈信息
     */
    private String extractStackTrace(String logLine) {
        int idx = Math.max(Math.max(logLine.indexOf("Exception"), logLine.indexOf("Error")), Math.max(logLine.indexOf("堆栈"), logLine.indexOf("stack trace")));
        return idx > -1 ? logLine.substring(idx) : "无异常栈";
    }
    
    /**
     * 使用 IntelligentRouter 评估异常日志
     */
    private void evaluateAnomaliesWithIntelligentRouter(List<EnhancedLogParseResultEntity> resultList, String taskId) {
        checkCancelStatus(taskId);

        int totalLogs = resultList.size();
        int errorLogs = (int) resultList.stream()
                .filter(log -> log.getSeverity() == LogSeverityLevel.ERROR_LEVEL || log.getSeverity() == LogSeverityLevel.FATAL_LEVEL)
                .count();
        double errorRate = totalLogs > 0 ? (double) errorLogs / totalLogs : 0.0;

        List<EnhancedLogParseResultEntity> candidates = new ArrayList<>();
        Set<String> seenTemplates = new HashSet<>();
        for (EnhancedLogParseResultEntity logEntity : resultList) {
            if (!logEntity.isAnomaly()
                    && logEntity.getSeverity() != LogSeverityLevel.ERROR_LEVEL
                    && logEntity.getSeverity() != LogSeverityLevel.FATAL_LEVEL) {
                continue;
            }
            String templateId = logEntity.getTemplateId();
            if (templateId != null && !templateId.isBlank() && !seenTemplates.add(templateId)) {
                continue;
            }
            candidates.add(logEntity);
        }

        candidates.sort(Comparator
                .comparingDouble(EnhancedLogParseResultEntity::getAnomalyScore).reversed()
                .thenComparing(r -> r.getSeverity().getSeverityScore(), Comparator.reverseOrder()));

        int candidateTotal = candidates.size();
        int evalLimit = Math.min(candidateTotal, ROUTER_EVAL_MAX);
        if (candidateTotal > evalLimit) {
            log.warn("【智能决策】候选 {} 条，按模板去重后截断为 {} 条以控制耗时", candidateTotal, evalLimit);
        }
        taskManager.updateProgress(taskId, 91, String.format("智能决策评估准备 (%d 条)...", evalLimit));

        for (int idx = 0; idx < evalLimit; idx++) {
            EnhancedLogParseResultEntity logEntity = candidates.get(idx);
            checkCancelStatus(taskId);

            if (idx == 0 || idx == evalLimit - 1 || idx % 5 == 0) {
                // 智能决策占整体进度 85%–97%，避免 91% 时仍显示 1/N 的错觉
                int progress = 91 + (idx + 1) * 6 / Math.max(1, evalLimit);
                taskManager.updateProgress(taskId, progress,
                        String.format("智能决策评估 %d/%d...", idx + 1, evalLimit));
            }

            RawLogEvent event = new RawLogEvent();
            event.setEventId(UUID.randomUUID().toString());
            event.setSourceId(taskId);
            event.setSourceType("file");
            event.setHost("localhost");
            event.setEventTime(logEntity.getTimestamp());
            event.setLevel(logEntity.getSeverity().name());
            event.setContent(logEntity.getDesensitizedLog());

            DecisionInput input = DecisionInput.builder()
                    .event(event)
                    .template(logEntity.getTemplateId())
                    .errorRate1m(errorRate)
                    .total1m(totalLogs)
                    .error1m(errorLogs)
                    .skipLlm(true)
                    .build();

            DecisionResult decisionResult = collaborativeDecisionService.decide(input);

            if (decisionResult.isShouldAlert()) {
                if (decisionResult.getConfidence() > 0.75) {
                    logEntity.getAnomalyReasons().add("智能决策告警: " + decisionResult.getReason()
                            + " (置信度: " + String.format("%.2f", decisionResult.getConfidence()) + ")");
                    double currentScore = logEntity.getAnomalyScore();
                    double confidenceBoost = decisionResult.getConfidence() * 0.1;
                    logEntity.setAnomalyScore(Math.min(1.0, currentScore + confidenceBoost));
                    logEntity.setAnomaly(true);
                    log.info("【智能决策】高置信度异常确认，模板: [{}], 原因: [{}], 置信度: [{}]",
                            logEntity.getTemplateId(), decisionResult.getReason(), decisionResult.getConfidence());
                } else {
                    log.info("【智能决策】低置信度告警，忽略，模板: [{}], 原因: [{}], 置信度: [{}]",
                            logEntity.getTemplateId(), decisionResult.getReason(), decisionResult.getConfidence());
                }
            } else if (logEntity.isAnomaly()) {
                double currentScore = logEntity.getAnomalyScore();
                double confidenceReduction = decisionResult.getConfidence() * 0.25;
                logEntity.setAnomalyScore(Math.max(0.0, currentScore - confidenceReduction));
                // 只降分，不撤销启发式已认定的异常（避免报告「被清掉」）
                log.info("【智能决策】降分保留启发式异常，模板: [{}], 原因: [{}], 置信度: [{}], score={}",
                        logEntity.getTemplateId(), decisionResult.getReason(), decisionResult.getConfidence(),
                        logEntity.getAnomalyScore());
            }
        }
    }
    
    // --- 文件生成 (保留原始逻辑) ---

    /**
     * 生成 HTML 可视化报告
     */
    private void generateHtmlReport(List<EnhancedLogParseResultEntity> resultList, EngineConfig config) throws IOException {
        File outDir = new File(config.outputDir);
        if (!outDir.exists()) outDir.mkdirs();
        String htmlPath = config.outputDir + File.separator + "log_analysis_report.html";
        Map<LogSeverityLevel, Integer> severityMap = new HashMap<>();
        Map<LogProtocolType, Integer> protocolMap = new HashMap<>();
        long anomalyCount = resultList.stream().filter(EnhancedLogParseResultEntity::isAnomaly).count();
        double anomalyRate = (double) anomalyCount / resultList.size() * 100;
        resultList.forEach(res -> {
            severityMap.put(res.getSeverity(), severityMap.getOrDefault(res.getSeverity(),0)+1);
            protocolMap.put(res.getProtocol(), protocolMap.getOrDefault(res.getProtocol(),0)+1);
        });
        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html><html lang=\"zh-CN\"><head>")
                .append("<meta charset=\"UTF-8\"><title>智能日志分析报告【赵文凯 日志分析组】</title>")
                .append("<script src=\"https://cdn.bootcdn.net/ajax/libs/echarts/5.4.3/echarts.min.js\"></script>")
                .append("<style>body{font-family:Microsoft YaHei,Arial;margin:20px;background:#fafafa;}")
                .append(".chart-box{width:100%;height:450px;margin:30px 0;border-radius:8px;background:#fff;padding:10px;box-shadow:0 2px 8px #eee;}")
                .append(".stat-container{display:flex;gap:20px;flex-wrap:wrap;margin:20px 0;}")
                .append(".stat-card{flex:1;min-width:220px;background:#fff;padding:25px;border-radius:12px;text-align:center;box-shadow:0 2px 12px #eee;}")
                .append(".stat-card h3{color:#333;margin:0;font-size:18px;}")
                .append(".stat-card p{color:#1E88E5;margin:10px 0;font-size:28px;font-weight:bold;}")
                .append(".anomaly-p{color:#FF5722!important;}table{width:100%;border-collapse:collapse;margin:30px 0;background:#fff;border-radius:8px;overflow:hidden;}")
                .append("table th{background:#1E88E5;color:#fff;padding:12px;text-align:left;}table td{border:1px solid #f0f0f0;padding:10px;}")
                .append("table tr:hover{background:#f9f9f9;}footer{margin:50px 0;text-align:center;color:#999;font-size:14px;}</style></head><body>");
        html.append("<h1 style=\"text-align:center;color:#1E88E5;margin:30px 0;\">智能日志分析引擎-可视化分析报告</h1>")
                .append("<div style=\"text-align:center;margin:20px 0;\">")
                .append(String.format("<h2 style=\"color:#1E88E5;margin:10px 0;\">总日志处理量: <span style=\"font-size:36px;font-weight:bold;\">%d 行</span></h2>", resultList.size()))
                .append(String.format("<h3 style=\"color:#FF5722;margin:10px 0;\">异常日志数量: <span style=\"font-size:28px;font-weight:bold;\">%d 行</span></h3>", anomalyCount))
                .append(String.format("<h3 style=\"color:#FF5722;margin:10px 0;\">日志异常率: <span style=\"font-size:28px;font-weight:bold;\">%.2f%%</span></h3>", anomalyRate))
                .append(String.format("<h3 style=\"color:#1E88E5;margin:10px 0;\">提取模板数: <span style=\"font-size:28px;font-weight:bold;\">%d 个</span></h3>",
                        resultList.stream().map(EnhancedLogParseResultEntity::getTemplateId).distinct().count()))
                .append("</div>");
        html.append("<div class=\"chart-box\" id=\"severityChart\"></div><script>")
                .append("var myChart = echarts.init(document.getElementById('severityChart'));")
                .append("myChart.setOption({title:{text:'日志等级风险分布',left:'center',fontSize:20},")
                .append("tooltip:{trigger:'item',formatter:'{b}: {c} 行 ({d}%)'},legend:{orient:'vertical',right:10,top:'center'},")
                .append("series:[{name:'等级分布',type:'pie',radius:['30%','70%'],roseType:'radius',")
                .append("itemStyle:{borderRadius:8,borderColor:'#fff',borderWidth:2},")
                .append("emphasis:{itemStyle:{shadowBlur:15,shadowColor:'rgba(0,0,0,0.3)'}},")
                .append("data:[");
        severityMap.forEach((severity, count) -> {
            html.append(String.format("{value:%d,name:'%s',itemStyle:{color:'%s'}}," ,
                    count, severity.getSeverityCnName(), severity.getColorCode()));
        });
        html.append("]}]});</script>");
        html.append("<h2 style=\"color:#1E88E5;margin:30px 0 10px;\">异常日志详情（前50条，故障定位核心）</h2>")
                .append("<table><tr><th>日志时间</th><th>风险等级</th><th>日志协议</th><th>进程/标签</th><th>Linux/容器解析</th><th>异常原因</th><th>异常得分</th><th>脱敏日志内容</th></tr>");
        resultList.stream().filter(EnhancedLogParseResultEntity::isAnomaly).limit(50).forEach(res -> {
            html.append("<tr>")
                    .append(String.format("<td>%s</td>", res.getLogTime() == null ? "未知" : res.getLogTime()))
                    .append(String.format("<td style=\"color:%s;font-weight:bold;\">%s</td>", res.getSeverity().getColorCode(), res.getSeverity().getSeverityCnName()))
                    .append(String.format("<td>%s</td>", res.getProtocol().getProtocolDesc()))
                    .append(String.format("<td>%s</td>", res.getPid()))
                    .append(String.format("<td style=\"max-width:220px;font-size:12px;\">%s</td>",
                            escapeHtml(LinuxLogParseSupport.formatParsedExtrasSummary(res.getFeatureMap()))))
                    .append(String.format("<td>%s</td>", String.join(" | ", res.getAnomalyReasons())))
                    .append(String.format("<td>%.2f</td>", res.getAnomalyScore()))
                    .append(String.format("<td style=\"max-width:500px;word-break:break-all;\">%s</td>", res.getDesensitizedLog()))
                    .append("</tr>");
        });
        html.append("</table><footer>")
                .append("© 2026 ThreshCore 智能日志分析引擎 | 赵文凯 日志分析组<br/>")
                .append("迭代式Drain算法+分级脱敏+四维异常检测+加权特征分析+多线程分片 | 运行环境：JDK8+ 零依赖</footer>")
                .append("</body></html>");
        try (BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(htmlPath), StandardCharsets.UTF_8))) {
            bw.write(html.toString());
        }
    }

    /**
     * 导出 CSV 结果文件
     */
    private void exportCsvResult(List<EnhancedLogParseResultEntity> resultList, EngineConfig config) throws IOException {
        File outDir = new File(config.outputDir);
        if (!outDir.exists()) outDir.mkdirs();
        String csvPath = config.outputDir + File.separator + "log_analysis_result.csv";
        try (BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(csvPath), StandardCharsets.UTF_8))) {
            bw.write('\ufeff');
            bw.write("日志时间,风险等级,日志协议,进程ID,Linux容器解析,用户ID,调用链ID,是否异常,异常原因,异常得分,风险加权得分,脱敏日志内容");
            bw.newLine();
            for (EnhancedLogParseResultEntity res : resultList) {
                List<String> fields = new ArrayList<>();
                fields.add(escapeCsv(res.getLogTime()));
                fields.add(escapeCsv(res.getSeverity().getSeverityCnName()));
                fields.add(escapeCsv(res.getProtocol().getProtocolDesc()));
                fields.add(escapeCsv(res.getPid()));
                fields.add(escapeCsv(LinuxLogParseSupport.formatParsedExtrasSummary(res.getFeatureMap())));
                fields.add(escapeCsv(res.getUid()));
                fields.add(escapeCsv(res.getTraceId()));
                fields.add(escapeCsv(res.isAnomaly() ? "是" : "否"));
                fields.add(escapeCsv(String.join("; ", res.getAnomalyReasons())));
                fields.add(escapeCsv(String.format("%.2f", res.getAnomalyScore())));
                fields.add(escapeCsv(String.format("%.2f", res.getRiskWeightScore())));
                fields.add(escapeCsv(res.getDesensitizedLog()));
                bw.write(String.join(",", fields));
                bw.newLine();
            }
        }
    }

    private static String escapeHtml(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    /**
     * 转义 CSV 字段内容
     */
    private String escapeCsv(String content) {
        if (content == null) content = "";
        if (content.contains(",") || content.contains("\"") || content.contains("\n") || content.contains("\r")) {
            return "\"" + content.replace("\"", "\"\"") + "\"";
        }
        return content;
    }


    // --- 内部类 (辅助逻辑) ---
    
    /**
     * 引擎配置类
     */
    private static class EngineConfig {
        String outputDir = System.getProperty("user.dir") + File.separator + "target" + File.separator + "output";
        boolean enableDesensitize = true;
        boolean enableAnomalyDetect = true;
        boolean enableVisualReport = true;
        boolean enableCsvExport = true;
        int batchSize = 6000;
        int threadPoolSize = Runtime.getRuntime().availableProcessors();
        double anomalyThreshold = 0.7;
        double frequencyThresholdRate = 0.05;
        int shardSize = 35000;
        int maxThreadPoolSize = Runtime.getRuntime().availableProcessors() * 5;
        double memoryWarnThreshold = 0.8;
        int minBatchSize = 1000;
    }

    /**
     * 日志分片工具类
     */
    private static class LogShardUtil {
        record SplitOutcome(List<List<String>> shards, int readLines, int skippedLines, boolean truncated) {
        }

        public static SplitOutcome splitLogToShards(InputStream inputStream, int shardSize, int maxLines)
                throws IOException {
            List<List<String>> shards = new ArrayList<>();
            int readLines = 0;
            int skippedLines = 0;
            boolean truncated = false;
            int cap = Math.max(1, maxLines);
            try (BufferedReader br = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
                String line;
                List<String> currentShard = new ArrayList<>(Math.min(shardSize, 4096));
                while ((line = br.readLine()) != null) {
                    if (line.trim().isEmpty()) continue;
                    if (readLines >= cap) {
                        truncated = true;
                        while (br.readLine() != null) {
                            skippedLines++;
                        }
                        break;
                    }
                    currentShard.add(line);
                    readLines++;
                    if (currentShard.size() >= shardSize) {
                        shards.add(currentShard);
                        currentShard = new ArrayList<>(Math.min(shardSize, 4096));
                    }
                }
                if (!currentShard.isEmpty()) {
                    shards.add(currentShard);
                }
            }
            return new SplitOutcome(shards, readLines, skippedLines, truncated);
        }
    }

    /**
     * 内存监控与动态调整工具类
     */
    private static class MemoryMonitor {
        // 内存使用率阈值
        private static final double MEMORY_THRESHOLD_HIGH = 0.8;
        private static final double MEMORY_THRESHOLD_MEDIUM = 0.6;
        private static final double MEMORY_THRESHOLD_LOW = 0.4;

        public static double checkMemoryUsage() {
            Runtime runtime = Runtime.getRuntime();
            long usedMemory = runtime.totalMemory() - runtime.freeMemory();
            long maxMemory = runtime.maxMemory();
            double usage = (double) usedMemory / maxMemory;
            log.info("内存使用情况: 已使用={}MB, 总内存={}MB, 使用率={}%", 
                    usedMemory / (1024 * 1024), maxMemory / (1024 * 1024), String.format("%.2f", usage * 100));
            return usage;
        }

        public static int adjustBatchSize(int currentBatchSize, int minBatchSize, double threshold) {
            double usage = checkMemoryUsage();
            int newBatchSize = currentBatchSize;
            
            if (usage > MEMORY_THRESHOLD_HIGH) {
                // 内存使用高，大幅减少批量大小
                newBatchSize = Math.max(minBatchSize, currentBatchSize / 4);
                log.info("内存使用率高 ({}), 批量大小从 {} 调整为 {}", String.format("%.2f", usage), currentBatchSize, newBatchSize);
            } else if (usage > MEMORY_THRESHOLD_MEDIUM) {
                // 内存使用中等，适度减少批量大小
                newBatchSize = Math.max(minBatchSize, currentBatchSize / 2);
                log.info("内存使用率中等 ({}), 批量大小从 {} 调整为 {}", String.format("%.2f", usage), currentBatchSize, newBatchSize);
            } else if (usage < MEMORY_THRESHOLD_LOW && currentBatchSize < 10000) {
                // 内存使用低，可以增加批量大小
                newBatchSize = Math.min(currentBatchSize * 2, 10000);
                log.info("内存使用率低 ({}), 批量大小从 {} 调整为 {}", String.format("%.2f", usage), currentBatchSize, newBatchSize);
            }
            
            return newBatchSize;
        }

        /**
         * 检查内存是否处于危险状态
         */
        public static boolean isMemoryCritical() {
            double usage = checkMemoryUsage();
            return usage > MEMORY_THRESHOLD_HIGH;
        }

        /**
         * 根据内存使用情况调整分片大小
         */
        public static int adjustShardSize(int currentShardSize) {
            double usage = checkMemoryUsage();
            int newShardSize = currentShardSize;
            
            if (usage > MEMORY_THRESHOLD_HIGH) {
                // 内存使用高，减少分片大小
                newShardSize = Math.max(5000, currentShardSize / 2);
                log.info("内存使用率高 ({}), 分片大小从 {} 调整为 {}", String.format("%.2f", usage), currentShardSize, newShardSize);
            } else if (usage < MEMORY_THRESHOLD_LOW) {
                // 内存使用低，增加分片大小
                newShardSize = Math.min(50000, currentShardSize * 2);
                log.info("内存使用率低 ({}), 分片大小从 {} 调整为 {}", String.format("%.2f", usage), currentShardSize, newShardSize);
            }
            
            return newShardSize;
        }
    }

}