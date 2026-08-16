package com.award.log.service.impl;

import com.award.log.mapper.ModelEvaluationMapper;
import com.award.log.model.ModelEvaluation;
import com.award.log.service.ModelEvaluationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class ModelEvaluationServiceImpl implements ModelEvaluationService {

    private final ModelEvaluationMapper modelEvaluationMapper;

    public ModelEvaluationServiceImpl(ModelEvaluationMapper modelEvaluationMapper) {
        this.modelEvaluationMapper = modelEvaluationMapper;
    }

    @Override
    public Map<String, Object> evaluate(String modelVersion, List<Map<String, Object>> dataset) {
        int tp = 0;
        int fp = 0;
        int tn = 0;
        int fn = 0;

        for (Map<String, Object> row : dataset) {
            @SuppressWarnings("unchecked")
            List<Number> features = (List<Number>) row.get("features");
            int label = ((Number) row.getOrDefault("label", 0)).intValue();
            double score = heuristic(features);
            int pred = score >= 0.5 ? 1 : 0;
            if (pred == 1 && label == 1) tp++;
            if (pred == 1 && label == 0) fp++;
            if (pred == 0 && label == 0) tn++;
            if (pred == 0 && label == 1) fn++;
        }

        double total = Math.max(1D, tp + fp + tn + fn);
        double accuracy = (tp + tn) / total;
        double precision = tp / Math.max(1D, (tp + fp));
        double recall = tp / Math.max(1D, (tp + fn));
        double f1 = (2D * precision * recall) / Math.max(1e-9, precision + recall);
        double rocAuc = (precision + recall) / 2D;
        double prAuc = f1;

        ModelEvaluation entity = new ModelEvaluation();
        entity.setModelVersion(modelVersion);
        entity.setSampleSize((int) total);
        entity.setAccuracy(accuracy);
        entity.setPrecisionScore(precision);
        entity.setRecallScore(recall);
        entity.setF1Score(f1);
        entity.setRocAuc(rocAuc);
        entity.setPrAuc(prAuc);
        entity.setConfusionMatrix(String.format("{\"tp\":%d,\"fp\":%d,\"tn\":%d,\"fn\":%d}", tp, fp, tn, fn));
        modelEvaluationMapper.insert(entity);

        Map<String, Object> report = new HashMap<>();
        report.put("modelVersion", modelVersion);
        report.put("sampleSize", (int) total);
        report.put("accuracy", accuracy);
        report.put("precision", precision);
        report.put("recall", recall);
        report.put("f1", f1);
        report.put("rocAuc", rocAuc);
        report.put("prAuc", prAuc);
        report.put("confusionMatrix", Map.of("tp", tp, "fp", fp, "tn", tn, "fn", fn));
        return report;
    }

    private double heuristic(List<Number> features) {
        if (features == null || features.isEmpty()) {
            return 0D;
        }
        double sum = 0D;
        for (Number n : features) {
            sum += Math.abs(n.doubleValue());
        }
        return Math.min(1D, sum / Math.max(1D, features.size()));
    }
}
