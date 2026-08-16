package com.award.log.controller;

import com.award.log.common.Result;
import com.award.log.service.ModelEvaluationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@Deprecated(since = "delivery-2026-07", forRemoval = false)
@Tag(name = "Model Evaluation", description = "非默认交付面：无挂载 UI / 仅 API，见 docs/deployment/交付API白名单.md")
@RestController
@RequestMapping("/api/v1/model")
public class ModelEvaluationController {

    private final ModelEvaluationService modelEvaluationService;

    public ModelEvaluationController(ModelEvaluationService modelEvaluationService) {
        this.modelEvaluationService = modelEvaluationService;
    }

    @Operation(summary = "评估模型效果")
    @PostMapping("/evaluate")
    public Result<Map<String, Object>> evaluate(@RequestBody Map<String, Object> payload) {
        String modelVersion = (String) payload.getOrDefault("modelVersion", "rf-onnx-v2");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> dataset = (List<Map<String, Object>>) payload.getOrDefault("dataset", List.of());
        if (dataset.isEmpty()) {
            return Result.error("dataset不能为空，需包含features和label字段");
        }
        return Result.success(modelEvaluationService.evaluate(modelVersion, dataset));
    }
}
