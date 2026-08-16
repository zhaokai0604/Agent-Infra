package com.award.log.controller;

import com.award.log.common.PageResult;
import com.award.log.common.Result;
import com.award.log.mapper.DecisionFeedbackMapper;
import com.award.log.mapper.DecisionLogMapper;
import com.award.log.model.DecisionFeedback;
import com.award.log.model.DecisionLog;
import com.award.log.security.RequestUserResolver;
import com.award.log.service.impl.ContinuousLearningService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@Tag(name = "决策反馈管理", description = "持续学习 API，无默认 UI：见 docs/deployment/交付API白名单.md")
@RestController
@RequestMapping("/api/decision-feedback")
@RequiredArgsConstructor
public class DecisionFeedbackController {

    private final DecisionFeedbackMapper decisionFeedbackMapper;
    private final DecisionLogMapper decisionLogMapper;
    private final ContinuousLearningService continuousLearningService;
    private final RequestUserResolver requestUserResolver;
    private final ObjectMapper objectMapper;

    @Operation(summary = "提交决策反馈")
    @PostMapping("/submit")
    public Result<Long> submitFeedback(@RequestBody DecisionFeedback feedback,
                                       HttpServletRequest request) {
        if (requestUserResolver.currentUserId(request) == null) {
            return Result.error(401, "请先登录");
        }
        log.info("[决策反馈] 收到反馈提交，decisionId: [{}], actualAlert: [{}]",
                feedback.getDecisionId(), feedback.getActualAlert());
        try {
            feedback.setIsTrained(false);
            hydrateWindowFeaturesFromDecisionLog(feedback);
            int rows = decisionFeedbackMapper.upsert(feedback);
            if (feedback.getId() == null && feedback.getDecisionId() != null) {
                DecisionFeedback stored = decisionFeedbackMapper.selectByDecisionId(feedback.getDecisionId());
                if (stored != null) {
                    feedback.setId(stored.getId());
                }
            }
            log.info("[决策反馈] 反馈提交成功，影响行数: [{}], id=[{}]", rows, feedback.getId());
            return Result.success(feedback.getId());
        } catch (Exception e) {
            log.error("[决策反馈] 反馈提交失败", e);
            return Result.error("反馈提交失败: " + e.getMessage());
        }
    }

    @Operation(summary = "批量提交决策反馈")
    @PostMapping("/submit/batch")
    public Result<Integer> submitFeedbackBatch(@RequestBody List<DecisionFeedback> feedbacks,
                                               HttpServletRequest request) {
        if (requestUserResolver.currentUserId(request) == null) {
            return Result.error(401, "请先登录");
        }
        if (feedbacks == null || feedbacks.isEmpty()) {
            return Result.error(400, "反馈列表不能为空");
        }
        log.info("[决策反馈] 收到批量反馈提交，数量: [{}]", feedbacks.size());
        int successCount = 0;
        for (DecisionFeedback feedback : feedbacks) {
            try {
                feedback.setIsTrained(false);
                hydrateWindowFeaturesFromDecisionLog(feedback);
                decisionFeedbackMapper.upsert(feedback);
                successCount++;
            } catch (Exception e) {
                log.error("[决策反馈] 批量提交单个反馈失败", e);
            }
        }
        return Result.success(successCount);
    }

    /**
     * 若提交未带窗口特征，尝试从 decision_log.input_json 回填（与 DecisionInput 对齐）。
     */
    private void hydrateWindowFeaturesFromDecisionLog(DecisionFeedback feedback) {
        if (feedback == null || feedback.getDecisionId() == null || feedback.getDecisionId().isBlank()) {
            return;
        }
        boolean needWindow = feedback.getErrorRate1m() == null
                || feedback.getError1m() == null
                || feedback.getTotal1m() == null
                || feedback.getIntervalMs() == null;
        boolean needMeta = (feedback.getLogContent() == null || feedback.getLogContent().isBlank())
                || (feedback.getLogLevel() == null || feedback.getLogLevel().isBlank());
        if (!needWindow && !needMeta) {
            return;
        }
        try {
            DecisionLog decisionLog = decisionLogMapper.selectByDecisionId(feedback.getDecisionId());
            if (decisionLog == null || decisionLog.getInputJson() == null || decisionLog.getInputJson().isBlank()) {
                return;
            }
            JsonNode root = objectMapper.readTree(decisionLog.getInputJson());
            if (needWindow) {
                if (feedback.getErrorRate1m() == null) {
                    feedback.setErrorRate1m(root.path("errorRate1m").asDouble(0));
                }
                if (feedback.getError1m() == null) {
                    feedback.setError1m(root.path("error1m").asDouble(0));
                }
                if (feedback.getTotal1m() == null) {
                    feedback.setTotal1m(root.path("total1m").asDouble(0));
                }
                if (feedback.getIntervalMs() == null) {
                    JsonNode event = root.path("event");
                    long eventTime = event.path("eventTime").asLong(0);
                    long ingestTime = event.path("ingestTime").asLong(eventTime);
                    feedback.setIntervalMs((double) Math.max(0L, ingestTime - eventTime));
                }
            }
            if (needMeta) {
                if (feedback.getLogContent() == null || feedback.getLogContent().isBlank()) {
                    String content = root.path("event").path("content").asText("");
                    if (!content.isBlank()) {
                        feedback.setLogContent(content);
                    }
                }
                if (feedback.getLogLevel() == null || feedback.getLogLevel().isBlank()) {
                    String level = root.path("event").path("level").asText("");
                    if (!level.isBlank()) {
                        feedback.setLogLevel(level);
                    }
                }
                if (feedback.getLogTemplate() == null || feedback.getLogTemplate().isBlank()) {
                    String template = root.path("template").asText("");
                    if (!template.isBlank()) {
                        feedback.setLogTemplate(template);
                    }
                }
                if (feedback.getModelConfidence() == null && decisionLog.getConfidence() != null) {
                    feedback.setModelConfidence(decisionLog.getConfidence());
                }
            }
        } catch (Exception e) {
            log.debug("[决策反馈] 从 decision_log 回填特征失败: {}", e.getMessage());
        }
    }

    @Operation(summary = "获取未训练样本列表")
    @GetMapping("/untrained")
    public Result<PageResult<DecisionFeedback>> getUntrainedSamples(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize,
            HttpServletRequest request) {
        if (!requestUserResolver.isAdmin(request)) {
            return Result.error(403, "仅管理员可查看未训练样本");
        }
        log.info("[决策反馈] 获取未训练样本，pageNum: [{}], pageSize: [{}]", pageNum, pageSize);
        try {
            int safePage = Math.max(1, pageNum);
            int safeSize = Math.min(Math.max(1, pageSize), 200);
            int offset = (safePage - 1) * safeSize;
            int total = decisionFeedbackMapper.countUntrained();
            List<DecisionFeedback> pageData = decisionFeedbackMapper.selectUntrainedSamplesPage(safeSize, offset);

            PageResult<DecisionFeedback> pageResult = new PageResult<>();
            pageResult.setList(pageData);
            pageResult.setTotal(total);

            return Result.success(pageResult);
        } catch (Exception e) {
            log.error("[决策反馈] 获取未训练样本失败", e);
            return Result.error("获取失败: " + e.getMessage());
        }
    }

    @Operation(summary = "获取未训练样本统计")
    @GetMapping("/untrained/count")
    public Result<Integer> getUntrainedCount(HttpServletRequest request) {
        if (!requestUserResolver.isAdmin(request)) {
            return Result.error(403, "仅管理员可查看未训练样本统计");
        }
        try {
            int count = decisionFeedbackMapper.countUntrained();
            return Result.success(count);
        } catch (Exception e) {
            log.error("[决策反馈] 获取未训练样本数失败", e);
            return Result.error("获取失败: " + e.getMessage());
        }
    }

    @Operation(summary = "手动触发模型训练")
    @PostMapping("/train/manual")
    public Result<Boolean> triggerManualTraining(HttpServletRequest request) {
        if (!requestUserResolver.isAdmin(request)) {
            return Result.error(403, "仅管理员可触发模型训练");
        }
        log.info("[决策反馈] 收到手动训练触发请求");
        try {
            boolean success = continuousLearningService.triggerManualTraining();
            return Result.success(success);
        } catch (Exception e) {
            log.error("[决策反馈] 手动训练触发失败", e);
            return Result.error("训练触发失败: " + e.getMessage());
        }
    }

    @Operation(summary = "标记样本为已训练")
    @PostMapping("/mark-trained")
    public Result<Integer> markAsTrained(@RequestBody List<Long> ids, HttpServletRequest request) {
        if (!requestUserResolver.isAdmin(request)) {
            return Result.error(403, "仅管理员可标记训练样本");
        }
        if (ids == null || ids.isEmpty()) {
            return Result.error(400, "样本 ID 列表不能为空");
        }
        log.info("[决策反馈] 标记样本为已训练，数量: [{}]", ids.size());
        try {
            int count = decisionFeedbackMapper.markAsTrained(ids);
            return Result.success(count);
        } catch (Exception e) {
            log.error("[决策反馈] 标记已训练失败", e);
            return Result.error("标记失败: " + e.getMessage());
        }
    }
}
