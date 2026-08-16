package com.award.log.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;

/**
 * 字符 n-gram TF-IDF + Softmax 线性模型（由 train_intent_risk_model.py 导出 JSON）。
 * 用于缓解意图规则膨胀：新攻击优先补样本重训，而非无限追加正则。
 */
@Slf4j
@Component
public class IntentRiskMlClassifier {

    private final ResourceLoader resourceLoader;
    private final ObjectMapper om = new ObjectMapper();
    private final String modelLocation;
    private final boolean enabled;

    private volatile boolean ready;
    private int ngramMin = 2;
    private int ngramMax = 4;
    private boolean sublinearTf = true;
    private Map<String, Integer> vocabulary = Map.of();
    private double[] idf = new double[0];
    private String[] classes = new String[0];
    private double[][] coef = new double[0][0];
    private double[] intercept = new double[0];
    private double minConfidence = 0.40;

    @Autowired
    public IntentRiskMlClassifier(
            ResourceLoader resourceLoader,
            @Value("${agent.security.intent-trained-model:classpath:security/intent-risk-model.json}") String modelLocation,
            @Value("${agent.security.intent-use-trained-ml:true}") boolean enabled
    ) {
        this.resourceLoader = resourceLoader;
        this.modelLocation = modelLocation;
        this.enabled = enabled;
    }

    /** 测试/离线构造：直接从已解析模型启用（非 Spring 注入入口）。 */
    IntentRiskMlClassifier(boolean enabled) {
        this.resourceLoader = null;
        this.modelLocation = "";
        this.enabled = enabled;
        this.ready = false;
    }

    /** 单测工厂，避免与 Spring 主构造器冲突。 */
    public static IntentRiskMlClassifier forOfflineLoad(boolean enabled) {
        return new IntentRiskMlClassifier(enabled);
    }

    @PostConstruct
    void init() {
        if (!enabled) {
            log.info("IntentRiskMlClassifier disabled by config");
            return;
        }
        try {
            Resource res = resourceLoader.getResource(modelLocation);
            if (!res.exists()) {
                log.warn("意图 ML 模型不存在: {}", modelLocation);
                return;
            }
            try (InputStream in = res.getInputStream()) {
                load(om.readTree(in));
            }
            ready = classes.length > 0 && vocabulary.size() > 0;
            log.info("IntentRiskMlClassifier loaded vocab={} classes={} minConfidence={}",
                    vocabulary.size(), classes.length, minConfidence);
        } catch (Exception e) {
            ready = false;
            log.warn("IntentRiskMlClassifier load failed: {}", e.getMessage());
        }
    }

    public void load(JsonNode root) {
        ngramMin = root.path("ngram_min").asInt(2);
        ngramMax = root.path("ngram_max").asInt(4);
        sublinearTf = root.path("sublinear_tf").asBoolean(true);
        minConfidence = root.path("min_confidence").asDouble(0.40);
        Map<String, Integer> vocab = new HashMap<>();
        Iterator<Map.Entry<String, JsonNode>> it = root.path("vocabulary").fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            vocab.put(e.getKey(), e.getValue().asInt());
        }
        this.vocabulary = vocab;
        JsonNode idfNode = root.path("idf");
        double[] idfArr = new double[idfNode.size()];
        for (int i = 0; i < idfArr.length; i++) {
            idfArr[i] = idfNode.get(i).asDouble();
        }
        this.idf = idfArr;
        JsonNode cls = root.path("classes");
        String[] classArr = new String[cls.size()];
        for (int i = 0; i < classArr.length; i++) {
            classArr[i] = cls.get(i).asText();
        }
        this.classes = classArr;
        JsonNode coefNode = root.path("coef");
        double[][] c = new double[coefNode.size()][];
        for (int k = 0; k < c.length; k++) {
            JsonNode row = coefNode.get(k);
            c[k] = new double[row.size()];
            for (int j = 0; j < row.size(); j++) {
                c[k][j] = row.get(j).asDouble();
            }
        }
        this.coef = c;
        JsonNode inter = root.path("intercept");
        double[] interceptArr = new double[inter.size()];
        for (int i = 0; i < interceptArr.length; i++) {
            interceptArr[i] = inter.get(i).asDouble();
        }
        this.intercept = interceptArr;
        this.ready = true;
    }

    public boolean isReady() {
        return enabled && ready;
    }

    public double getMinConfidence() {
        return minConfidence;
    }

    /**
     * @return 预测结果；模型未就绪时返回 null
     */
    public Prediction predict(String text) {
        if (!isReady() || text == null || text.isBlank()) {
            return null;
        }
        String doc = text.toLowerCase(Locale.ROOT);
        double[] x = vectorize(doc);
        int nClass = classes.length;
        double[] logits = new double[nClass];
        for (int k = 0; k < nClass; k++) {
            double s = intercept[k];
            double[] ck = coef[k];
            int lim = Math.min(ck.length, x.length);
            for (int j = 0; j < lim; j++) {
                if (x[j] != 0.0) {
                    s += ck[j] * x[j];
                }
            }
            logits[k] = s;
        }
        double[] proba = softmax(logits);
        int best = 0;
        for (int k = 1; k < nClass; k++) {
            if (proba[k] > proba[best]) {
                best = k;
            }
        }
        RiskLevel level;
        try {
            level = RiskLevel.valueOf(classes[best]);
        } catch (Exception e) {
            level = RiskLevel.MEDIUM;
        }
        return new Prediction(level, proba[best], classes[best]);
    }

    private double[] vectorize(String doc) {
        Map<Integer, Integer> tf = new HashMap<>();
        for (int n = ngramMin; n <= ngramMax; n++) {
            if (doc.length() < n) {
                continue;
            }
            for (int i = 0; i + n <= doc.length(); i++) {
                String g = doc.substring(i, i + n);
                Integer idx = vocabulary.get(g);
                if (idx != null) {
                    tf.merge(idx, 1, Integer::sum);
                }
            }
        }
        double[] vec = new double[idf.length];
        for (Map.Entry<Integer, Integer> e : tf.entrySet()) {
            int j = e.getKey();
            if (j < 0 || j >= vec.length) {
                continue;
            }
            double c = e.getValue();
            double term = sublinearTf ? (1.0 + Math.log(c)) : c;
            vec[j] = term * idf[j];
        }
        double norm = 0.0;
        for (double v : vec) {
            norm += v * v;
        }
        norm = Math.sqrt(norm);
        if (norm > 0) {
            for (int i = 0; i < vec.length; i++) {
                vec[i] /= norm;
            }
        }
        return vec;
    }

    private static double[] softmax(double[] zs) {
        double max = zs[0];
        for (double z : zs) {
            if (z > max) {
                max = z;
            }
        }
        double[] out = new double[zs.length];
        double sum = 0.0;
        for (int i = 0; i < zs.length; i++) {
            out[i] = Math.exp(zs[i] - max);
            sum += out[i];
        }
        if (sum <= 0) {
            return out;
        }
        for (int i = 0; i < out.length; i++) {
            out[i] /= sum;
        }
        return out;
    }

    public record Prediction(RiskLevel level, double confidence, String label) {
    }
}
