package com.award.log.analyzer;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Drain 在线日志解析完整实现（论文流程，含 Drain 扩展 commonly called Drain+/Spell 家族思路）：
 * <ol>
 *   <li>对原始日志做变量掩码（UUID、IPv4、浮点、整数等）→ 令牌序列</li>
 *   <li>按<strong>令牌个数</strong>分桶（长度树第一层，减少误合并）</li>
 *   <li>以前缀深度 {@code maxDepth} 在 Trie 上导航（掩码后的 token 作边键）</li>
 *   <li>在叶节点簇（cluster list）内按<strong>序列相似度</strong>寻找最佳模板；
 *       达到阈值则<strong>合并</strong>：不一致位置泛化为 {@link #PARAM_TOKEN}</li>
 *   <li>无匹配则新建簇并分配单调模板 ID</li>
 * </ol>
 * <p>
 * 线程安全：按令牌长度分桶后仅在桶内加锁，并行分片解析时不同长度桶可并发写入。
 */
@Component
@Qualifier("drainPlusParser")
public class DrainPlusParser implements DrainParser {

    /** 与 Logpai / 文献一致的参数占位符 */
    public static final String PARAM_TOKEN = "<*>";

    private static final Pattern TOKEN_WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern TOKEN_UUID = Pattern.compile(
            "(?i)[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}");
    private static final Pattern TOKEN_IPV4 = Pattern.compile(
            "(?<!\\d)(?:\\d{1,3}\\.){3}\\d{1,3}(?!\\d)");
    private static final Pattern TOKEN_FLOAT = Pattern.compile("\\d+\\.\\d+");
    private static final Pattern TOKEN_INT = Pattern.compile("\\d+");

    private final int maxDepth;
    /**
     * 相似度阈值（百分比 1–100）：相似度 = 匹配令牌数 / max(模板长, 日志长)，
     * 其中模板侧 {@link #PARAM_TOKEN} 与任意令牌视为匹配。
     */
    private final int similarityThresholdPercent;

    private final Map<Integer, LengthBucket> lengthBuckets = new ConcurrentHashMap<>();
    private final AtomicLong templateCounter = new AtomicLong(1);

    @Autowired
    public DrainPlusParser(
            @Value("${log.analyzer.drain.max-depth:8}") int maxDepth,
            @Value("${log.analyzer.drain.sim-threshold:50}") int similarityThresholdPercent) {
        this.maxDepth = Math.max(1, Math.min(32, maxDepth));
        this.similarityThresholdPercent = clamp(similarityThresholdPercent, 1, 100);
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    @Override
    public String parse(String raw) {
        return parse(raw, null);
    }

    @Override
    public String parse(String raw, Consumer<TemplateInfo> onNewTemplate) {
        if (raw == null || raw.isBlank()) {
            return "EMPTY";
        }
        String[] tokens = preprocessAndTokenize(raw);
        if (tokens.length == 1 && "EMPTY".equals(tokens[0])) {
            return "EMPTY";
        }
        int len = tokens.length;
        LengthBucket bucket = lengthBuckets.computeIfAbsent(len, k -> new LengthBucket());
        synchronized (bucket) {
            TrieNode leaf = bucket.walkOrCreateLeaf(tokens, maxDepth);
            return leaf.matchOrCreateCluster(tokens, raw, onNewTemplate, templateCounter, similarityThresholdPercent);
        }
    }

    @Override
    public List<TemplateInfo> parallelParse(List<String> rawLogs, int threads) {
        if (rawLogs == null || rawLogs.isEmpty()) {
            return Collections.emptyList();
        }
        List<TemplateInfo> results = Collections.synchronizedList(new ArrayList<>());
        int nThreads = Math.max(1, threads);
        int partitionSize = Math.max(1, rawLogs.size() / nThreads);

        Thread[] threadList = new Thread[nThreads];
        for (int t = 0; t < nThreads; t++) {
            int start = t * partitionSize;
            int end = Math.min(start + partitionSize, rawLogs.size());
            if (start >= rawLogs.size()) {
                break;
            }
            threadList[t] = new Thread(() -> {
                for (int i = start; i < end; i++) {
                    parse(rawLogs.get(i), results::add);
                }
            }, "drain-plus-worker-" + t);
            threadList[t].start();
        }

        for (Thread thread : threadList) {
            if (thread != null) {
                try {
                    thread.join();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        return results;
    }

    @Override
    public int getTemplateCount() {
        int sum = 0;
        for (LengthBucket b : lengthBuckets.values()) {
            synchronized (b) {
                sum += b.clusterCount();
            }
        }
        return sum;
    }

    @Override
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("version", "plus-canonical");
        stats.put("algorithm", "Drain: length-bucket + prefix trie + cluster similarity + <*> merge");
        stats.put("totalTemplates", getTemplateCount());
        stats.put("maxDepth", maxDepth);
        stats.put("simThreshold", similarityThresholdPercent);
        stats.put("lengthBuckets", lengthBuckets.size());
        stats.put("paramToken", PARAM_TOKEN);
        return stats;
    }

    @Override
    public String getVersion() {
        return "DrainParserPlus";
    }

    // --- 预处理：掩码后分词（顺序：UUID → IPv4 → float → int） ---

    static String[] preprocessAndTokenize(String raw) {
        String s = raw.trim();
        if (s.isEmpty()) {
            return new String[]{"EMPTY"};
        }
        s = TOKEN_UUID.matcher(s).replaceAll(PARAM_TOKEN);
        s = TOKEN_IPV4.matcher(s).replaceAll(PARAM_TOKEN);
        s = TOKEN_FLOAT.matcher(s).replaceAll(PARAM_TOKEN);
        s = TOKEN_INT.matcher(s).replaceAll(PARAM_TOKEN);
        s = TOKEN_WHITESPACE.matcher(s).replaceAll(" ").trim();
        if (s.isEmpty()) {
            return new String[]{"EMPTY"};
        }
        return s.split(" ");
    }

    /**
     * 序列相似度 ∈ [0,1]。模板中的 {@link #PARAM_TOKEN} 与任意日志令牌计为匹配。
     */
    static double similarity(String[] template, String[] logTokens) {
        int n = Math.max(template.length, logTokens.length);
        if (n == 0) {
            return 1.0;
        }
        int lim = Math.min(template.length, logTokens.length);
        int match = 0;
        for (int i = 0; i < lim; i++) {
            String a = template[i];
            String b = logTokens[i];
            if (a.equals(b)) {
                match++;
            } else if (PARAM_TOKEN.equals(a)) {
                match++;
            }
        }
        return (double) match / n;
    }

    /** 合并模板：逐位置相同保留字面，否则泛化为 {@link #PARAM_TOKEN}；长度取较大者。 */
    static String[] mergeTemplates(String[] template, String[] logTokens) {
        int max = Math.max(template.length, logTokens.length);
        String[] out = new String[max];
        for (int i = 0; i < max; i++) {
            String a = i < template.length ? template[i] : PARAM_TOKEN;
            String b = i < logTokens.length ? logTokens[i] : PARAM_TOKEN;
            if (a.equals(b)) {
                out[i] = a;
            } else if (PARAM_TOKEN.equals(a) || PARAM_TOKEN.equals(b)) {
                out[i] = PARAM_TOKEN;
            } else {
                out[i] = PARAM_TOKEN;
            }
        }
        return out;
    }

    private static final class LengthBucket {
        private final TrieNode root = new TrieNode("<root>");

        LengthBucket() {
        }

        int clusterCount() {
            return root.countClustersRecursive();
        }

        TrieNode walkOrCreateLeaf(String[] tokens, int maxDepth) {
            TrieNode cur = root;
            int steps = Math.min(maxDepth, tokens.length);
            for (int i = 0; i < steps; i++) {
                String key = tokens[i];
                cur = cur.children.computeIfAbsent(key, TrieNode::new);
            }
            return cur;
        }
    }

    private static final class TrieNode {
        private final String key;
        private final Map<String, TrieNode> children = new ConcurrentHashMap<>();
        private final List<LogCluster> clusters = new ArrayList<>();

        TrieNode(String key) {
            this.key = key;
        }

        int countClustersRecursive() {
            int n = clusters.size();
            for (TrieNode c : children.values()) {
                n += c.countClustersRecursive();
            }
            return n;
        }

        String matchOrCreateCluster(
                String[] tokens,
                String rawLine,
                Consumer<TemplateInfo> onNewTemplate,
                AtomicLong templateCounter,
                int similarityThresholdPercent) {
            double threshold = similarityThresholdPercent / 100.0;
            LogCluster best = null;
            double bestSim = -1.0;
            for (LogCluster c : clusters) {
                double sim = similarity(c.templateTokens, tokens);
                if (sim > bestSim) {
                    bestSim = sim;
                    best = c;
                }
            }
            if (best != null && bestSim >= threshold) {
                best.templateTokens = mergeTemplates(best.templateTokens, tokens);
                best.representativeLine = rawLine;
                best.hits.incrementAndGet();
                return best.templateId;
            }

            String newId = "TMP-" + templateCounter.getAndIncrement();
            LogCluster nc = new LogCluster(newId, tokens.clone(), rawLine);
            clusters.add(nc);
            if (onNewTemplate != null) {
                onNewTemplate.accept(nc);
            }
            return newId;
        }
    }

    private static final class LogCluster implements TemplateInfo {
        private final String templateId;
        private volatile String[] templateTokens;
        private volatile String representativeLine;
        private final AtomicLong hits = new AtomicLong(1);

        LogCluster(String templateId, String[] templateTokens, String representativeLine) {
            this.templateId = templateId;
            this.templateTokens = templateTokens;
            this.representativeLine = representativeLine;
        }

        @Override
        public String getTemplateId() {
            return templateId;
        }

        @Override
        public String getTemplateText() {
            return representativeLine != null ? representativeLine : String.join(" ", templateTokens);
        }

        @Override
        public long getCount() {
            return hits.get();
        }
    }
}
