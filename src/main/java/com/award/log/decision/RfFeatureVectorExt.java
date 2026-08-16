package com.award.log.decision;

import com.award.log.collector.model.RawLogEvent;
import lombok.Builder;
import lombok.Getter;

import java.util.Locale;

/**
 * 扩展随机森林特征向量（固定25维）。
 */
@Getter
@Builder
public class RfFeatureVectorExt {

    public static final int FEATURE_SIZE = 25;

    private final float[] features;

    public float[] toArray() {
        float[] copy = new float[FEATURE_SIZE];
        System.arraycopy(features, 0, copy, 0, FEATURE_SIZE);
        return copy;
    }

    public static RfFeatureVectorExt fromDecisionInputExt(DecisionInput input) {
        float[] data = new float[FEATURE_SIZE];
        RawLogEvent event = input.getEvent();
        String level = event == null || event.getLevel() == null ? "INFO" : event.getLevel().toUpperCase(Locale.ROOT);
        String content = event == null || event.getContent() == null ? "" : event.getContent().toLowerCase(Locale.ROOT);
        String template = input.getTemplate() == null ? "" : input.getTemplate().toLowerCase(Locale.ROOT);

        // 0-4: 日志级别 one-hot [TRACE,DEBUG,INFO,WARN,ERROR/FATAL]
        data[0] = "TRACE".equals(level) ? 1f : 0f;
        data[1] = "DEBUG".equals(level) ? 1f : 0f;
        data[2] = "INFO".equals(level) ? 1f : 0f;
        data[3] = "WARN".equals(level) ? 1f : 0f;
        data[4] = ("ERROR".equals(level) || "FATAL".equals(level)) ? 1f : 0f;

        // 5-8: 时间间隔统计（基于eventTime与ingestTime近似）
        long eventTime = event == null || event.getEventTime() == null ? 0L : event.getEventTime();
        long ingestTime = event == null || event.getIngestTime() == null ? eventTime : event.getIngestTime();
        double intervalMs = Math.max(0D, ingestTime - eventTime);
        float normInterval = (float) Math.min(1D, intervalMs / 10000D);
        data[5] = normInterval;
        data[6] = normInterval * normInterval;
        data[7] = normInterval;
        data[8] = 0f;

        // 9-13: 模板词向量哈希（简单哈希桶占比）
        for (String token : template.split("\\s+")) {
            if (token.isBlank()) {
                continue;
            }
            int idx = Math.abs(token.hashCode()) % 5;
            data[9 + idx] += 1f;
        }
        normalizeSlice(data, 9, 13);

        // 14-16: 关键词 TF-IDF Top-3 近似（error/exception/timeout）
        data[14] = keywordScore(content, "error");
        data[15] = keywordScore(content, "exception");
        data[16] = keywordScore(content, "timeout");

        // 17-19: 错误率滑动窗口（1m/错误数归一化/总量归一化）
        data[17] = (float) input.getErrorRate1m();
        data[18] = (float) Math.min(1D, input.getError1m() / 100D);
        data[19] = (float) Math.min(1D, input.getTotal1m() / 300D);

        // 20: 调用链深度（通过“at ”数量近似）
        int stackDepth = count(content, " at ");
        data[20] = (float) Math.min(1D, stackDepth / 30D);

        // 21: 空间熵特征（简单字符分布熵）
        data[21] = (float) entropy(content);

        // 22-24: 协议类型编码（db/http/other）
        data[22] = containsAny(content, "mysql", "sql", "jdbc") ? 1f : 0f;
        data[23] = containsAny(content, "http", "nginx", "apache", "status") ? 1f : 0f;
        data[24] = (data[22] == 0f && data[23] == 0f) ? 1f : 0f;

        return RfFeatureVectorExt.builder().features(data).build();
    }

    private static float keywordScore(String text, String keyword) {
        if (text.isBlank()) {
            return 0f;
        }
        int c = count(text, keyword);
        return (float) Math.min(1D, c / 3D);
    }

    private static boolean containsAny(String text, String... keys) {
        for (String key : keys) {
            if (text.contains(key)) {
                return true;
            }
        }
        return false;
    }

    private static int count(String text, String sub) {
        int count = 0;
        int i = 0;
        while ((i = text.indexOf(sub, i)) >= 0) {
            count++;
            i += sub.length();
        }
        return count;
    }

    private static void normalizeSlice(float[] data, int start, int end) {
        float sum = 0f;
        for (int i = start; i <= end; i++) {
            sum += data[i];
        }
        if (sum <= 0f) {
            return;
        }
        for (int i = start; i <= end; i++) {
            data[i] = data[i] / sum;
        }
    }

    private static double entropy(String text) {
        if (text.isBlank()) {
            return 0D;
        }
        int[] freq = new int[128];
        int n = 0;
        for (char ch : text.toCharArray()) {
            if (ch < 128) {
                freq[ch]++;
                n++;
            }
        }
        if (n == 0) {
            return 0D;
        }
        double entropy = 0D;
        for (int f : freq) {
            if (f == 0) {
                continue;
            }
            double p = (double) f / n;
            entropy -= p * (Math.log(p) / Math.log(2D));
        }
        return Math.min(1D, entropy / 7D);
    }
}
