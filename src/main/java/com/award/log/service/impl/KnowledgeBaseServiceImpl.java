package com.award.log.service.impl;

import com.award.log.config.KnowledgeProperties;
import com.award.log.knowledge.KnowledgeSeedData;
import com.award.log.service.KnowledgeBaseService;
import com.award.log.util.KnowledgeDocumentChunker;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.reader.markdown.MarkdownDocumentReader;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import jakarta.annotation.PostConstruct;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
public class KnowledgeBaseServiceImpl implements KnowledgeBaseService {

    @Value("${qdrant.url:http://localhost:6333}")
    private String qdrantUrl;

    @Value("${qdrant.api-key:}")
    private String qdrantApiKey;

    @Value("${qdrant.collection:ops_knowledge}")
    private String collectionName;

    private final KnowledgeProperties knowledgeProperties;
    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired(required = false)
    private EmbeddingModel embeddingModel;

    /** 进程内集合状态：TRUE=已确认存在，避免重复 GET/POST 创建 */
    private final Object collectionLock = new Object();
    private volatile Boolean collectionReady = null;
    private volatile long collectionProbeAtMs = 0;
    private static final long COLLECTION_PROBE_TTL_MS = 120_000;

    private volatile long cachedDocumentCount = -1;
    private volatile long documentCountCachedAtMs = 0;
    private static final long DOCUMENT_COUNT_TTL_MS = 120_000;

    private volatile List<Map<String, Object>> cachedScrollRows;
    private volatile long cachedScrollAtMs = 0;
    private static final long SCROLL_CACHE_TTL_MS = 30_000;

    public KnowledgeBaseServiceImpl(KnowledgeProperties knowledgeProperties) {
        this.knowledgeProperties = knowledgeProperties;
    }

    @PostConstruct
    void normalizeQdrantEndpoint() {
        qdrantUrl = normalizeQdrantUrl(qdrantUrl);
    }

    @PostConstruct
    void scheduleKnowledgeSeed() {
        if (!knowledgeProperties.isEnabled() || !knowledgeProperties.isSeedOnStartup()) {
            return;
        }
        Thread seedThread = new Thread(() -> {
            try {
                Thread.sleep(4000);
                seedIfEmpty();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                log.debug("[知识库] 启动 seed 跳过: {}", e.getMessage());
            }
        }, "kb-seed");
        seedThread.setDaemon(true);
        seedThread.start();
    }

    private void seedIfEmpty() {
        if (getJson("/collections") == null) {
            log.warn("[知识库] seed 跳过：无法连接 Qdrant url={}", qdrantUrl);
            return;
        }
        ensureCollection();
        invalidateDocumentCountCache();
        refreshDocumentCountCache();
        if (cachedDocumentCount > 0) {
            log.info("[知识库] 已有 {} 篇文档，跳过 seed", cachedDocumentCount);
            return;
        }
        int ok = 0;
        for (KnowledgeSeedData.Entry entry : KnowledgeSeedData.all()) {
            try {
                upload(entry.title(), entry.content(), entry.category(), "seed");
                ok++;
            } catch (Exception e) {
                log.warn("[知识库] seed「{}」失败: {}", entry.title(), e.getMessage());
            }
        }
        if (ok > 0) {
            invalidateDocumentCountCache();
            log.info("[知识库] 冷启动 seed 写入 {} 篇内置 Runbook", ok);
        } else {
            log.warn("[知识库] seed 未写入任何文档，请检查 Qdrant PUT 权限与 Embedding");
        }
    }

    @Override
    public Map<String, Object> seedBuiltinIfEmpty() {
        Map<String, Object> out = new LinkedHashMap<>();
        if (!knowledgeProperties.isEnabled()) {
            out.put("seeded", 0);
            out.put("skipped", true);
            out.put("reason", "knowledge disabled");
            return out;
        }
        long before = 0;
        try {
            if (isCollectionPresent()) {
                refreshDocumentCountCache();
                before = Math.max(0, cachedDocumentCount);
            }
        } catch (Exception ignored) {
            // ignore
        }
        seedIfEmpty();
        refreshDocumentCountCache();
        long after = Math.max(0, cachedDocumentCount);
        out.put("seeded", Math.max(0, after - before));
        out.put("documentCount", after);
        out.put("qdrantUrl", qdrantUrl);
        out.put("collection", collectionName);
        return out;
    }

    @Override
    public Map<String, Object> upload(String title, String content) {
        return upload(title, content, "general", "manual");
    }

    @Override
    public Map<String, Object> upload(String title, String content, String category, String source) {
        if (!knowledgeProperties.isEnabled()) {
            throw new IllegalStateException("知识库未启用");
        }
        String safeTitle = blankTo(title, "untitled");
        String safeCategory = blankTo(category, "general");
        String safeSource = blankTo(source, "manual");
        List<String> chunks = KnowledgeDocumentChunker.chunk(
                content, knowledgeProperties.getChunkSize(), knowledgeProperties.getChunkOverlap());
        if (chunks.isEmpty()) {
            throw new IllegalArgumentException("文档内容为空");
        }
        ensureCollection();
        String documentId = UUID.randomUUID().toString().replace("-", "");
        String createdAt = Instant.now().toString();
        List<Map<String, Object>> points = new ArrayList<>();
        int idx = 0;
        for (String chunk : chunks) {
            String pointId = UUID.randomUUID().toString().replace("-", "");
            List<Double> vector = toDoubleList(embed(chunk));
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("title", safeTitle);
            payload.put("content", chunk);
            payload.put("documentId", documentId);
            payload.put("chunkIndex", idx);
            payload.put("chunkTotal", chunks.size());
            payload.put("category", safeCategory);
            payload.put("source", safeSource);
            payload.put("createdAt", createdAt);
            points.add(Map.of(
                    "id", pointId,
                    "vector", vector,
                    "payload", payload
            ));
            idx++;
        }
        upsertPoints(points);
        invalidateDocumentCountCache();
        log.info("[知识库] 文档已入库 documentId={} title={} chunks={}", documentId, safeTitle, chunks.size());
        return Map.of(
                "documentId", documentId,
                "title", safeTitle,
                "category", safeCategory,
                "source", safeSource,
                "chunkCount", chunks.size(),
                "createdAt", createdAt
        );
    }

    @Override
    public List<Map<String, Object>> uploadFile(MultipartFile file, String title, String category) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("文件为空");
        }
        String filename = file.getOriginalFilename() == null ? "upload" : file.getOriginalFilename();
        String ext = filename.contains(".")
                ? filename.substring(filename.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT)
                : "txt";
        String docTitle = blankTo(title, filename);
        String text = extractText(file, ext);
        Map<String, Object> meta = upload(docTitle, text, category, filename);
        return List.of(meta);
    }

    @Override
    public List<Map<String, Object>> search(String query, int topK) {
        if (!knowledgeProperties.isEnabled()) {
            return List.of();
        }
        if (query == null || query.isBlank()) {
            return List.of();
        }
        if (!isCollectionPresent()) {
            return List.of();
        }
        int limit = Math.max(1, Math.min(topK, 20));
        List<Double> queryVector = toDoubleList(embed(query.trim()));
        Map<String, Object> body = Map.of(
                "vector", queryVector,
                "limit", limit,
                "with_payload", true
        );
        JsonNode json = post("/collections/" + collectionName + "/points/search", body);
        List<Map<String, Object>> result = new ArrayList<>();
        if (json != null && json.has("result") && json.path("result").isArray()) {
            for (JsonNode item : json.path("result")) {
                JsonNode payload = item.path("payload");
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", item.path("id").asText());
                row.put("title", payload.path("title").asText(""));
                row.put("content", payload.path("content").asText(""));
                row.put("documentId", payload.path("documentId").asText(""));
                row.put("chunkIndex", payload.path("chunkIndex").asInt(0));
                row.put("category", payload.path("category").asText(""));
                row.put("source", payload.path("source").asText(""));
                row.put("score", item.path("score").asDouble(0));
                result.add(row);
            }
        }
        if (result.isEmpty()) {
            result = keywordSearch(query.trim(), limit);
        }
        log.info("[知识库] 检索 query={} hits={}", abbreviate(query, 80), result.size());
        return result;
    }

    /** 向量检索无命中时，按关键词重叠降级检索 */
    private List<Map<String, Object>> keywordSearch(String query, int limit) {
        List<String> tokens = tokenize(query);
        if (tokens.isEmpty()) {
            return List.of();
        }
        try {
            ensureCollection();
        } catch (Exception e) {
            return List.of();
        }
        List<Map<String, Object>> scored = new ArrayList<>();
        for (Map<String, Object> chunk : scrollAllChunksCached()) {
            String hay = (String.valueOf(chunk.getOrDefault("title", "")) + ' '
                    + String.valueOf(chunk.getOrDefault("content", ""))).toLowerCase(Locale.ROOT);
            int hits = 0;
            for (String t : tokens) {
                if (hay.contains(t)) {
                    hits++;
                }
            }
            if (hits <= 0) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>(chunk);
            row.put("score", hits / (double) tokens.size());
            scored.add(row);
        }
        scored.sort((a, b) -> Double.compare(
                (Double) b.getOrDefault("score", 0.0),
                (Double) a.getOrDefault("score", 0.0)));
        return scored.size() <= limit ? scored : scored.subList(0, limit);
    }

    private static List<String> tokenize(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(text.toLowerCase(Locale.ROOT).split("\\s+"))
                .map(String::trim)
                .filter(s -> s.length() >= 2)
                .distinct()
                .limit(12)
                .toList();
    }

    @Override
    public boolean delete(String pointId) {
        if (pointId == null || pointId.isBlank()) {
            return false;
        }
        ensureCollection();
        post("/collections/" + collectionName + "/points/delete", Map.of("points", List.of(pointId)));
        invalidateDocumentCountCache();
        return true;
    }

    @Override
    public boolean deleteDocument(String documentId) {
        if (documentId == null || documentId.isBlank()) {
            return false;
        }
        ensureCollection();
        Map<String, Object> filter = Map.of(
                "must", List.of(Map.of(
                        "key", "documentId",
                        "match", Map.of("value", documentId)
                ))
        );
        post("/collections/" + collectionName + "/points/delete", Map.of("filter", filter));
        invalidateDocumentCountCache();
        log.info("[知识库] 已删除文档 documentId={}", documentId);
        return true;
    }

    @Override
    public Map<String, Object> getStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("enabled", knowledgeProperties.isEnabled());
        status.put("collection", collectionName);
        status.put("qdrantUrl", qdrantUrl);
        status.put("apiKeyConfigured", qdrantApiKey != null && !qdrantApiKey.isBlank());
        status.put("embeddingModel", embeddingModel != null ? "available" : "unavailable");
        status.put("vectorDimensions", knowledgeProperties.getVectorDimensions());
        status.put("embeddingMode", resolveEmbeddingMode());
        status.put("semanticSearchReady", "spring-ai".equals(resolveEmbeddingMode()));

        boolean qdrantReachable = false;
        boolean collectionExists = false;
        long pointCount = 0;
        String probeHint = "";

        JsonNode collectionsRoot = getJson("/collections");
        if (collectionsRoot != null && collectionsRoot.has("result")) {
            qdrantReachable = true;
            collectionExists = isCollectionPresent();
            if (collectionExists) {
                JsonNode info = getJson("/collections/" + collectionName);
                if (info != null && info.has("result")) {
                    pointCount = info.path("result").path("points_count").asLong(0);
                }
            }
        } else {
            if (qdrantApiKey == null || qdrantApiKey.isBlank()) {
                probeHint = "无法连接 Qdrant，Cloud 集群需配置 api-key";
            } else {
                probeHint = "无法连接 Qdrant，请检查 URL 与 API Key";
            }
        }

        if (!collectionExists && qdrantReachable) {
            probeHint = "集群已连接，集合将在首次上传文档时创建";
        }

        status.put("qdrantConnected", qdrantReachable);
        status.put("collectionExists", collectionExists);
        status.put("probeHint", probeHint);
        status.put("pointCount", pointCount);
        status.put("documentCount", collectionExists ? resolveDocumentCountCached() : 0L);
        status.put("ready", knowledgeProperties.isEnabled() && qdrantReachable && collectionExists);
        return status;
    }

    private long resolveDocumentCountCached() {
        long now = System.currentTimeMillis();
        if (cachedDocumentCount >= 0 && now - documentCountCachedAtMs < DOCUMENT_COUNT_TTL_MS) {
            return cachedDocumentCount;
        }
        refreshDocumentCountCache();
        return Math.max(0L, cachedDocumentCount);
    }

    private void refreshDocumentCountCache() {
        try {
            cachedDocumentCount = scrollAllChunksCached().stream()
                    .map(r -> String.valueOf(r.getOrDefault("documentId", "")))
                    .filter(s -> !s.isBlank())
                    .distinct()
                    .count();
            documentCountCachedAtMs = System.currentTimeMillis();
        } catch (Exception e) {
            log.debug("[知识库] 刷新文档数缓存失败: {}", e.getMessage());
        }
    }

    private void invalidateDocumentCountCache() {
        cachedDocumentCount = -1;
        documentCountCachedAtMs = 0;
        invalidateScrollCache();
    }

    private void invalidateScrollCache() {
        cachedScrollRows = null;
        cachedScrollAtMs = 0;
    }

    private List<Map<String, Object>> scrollAllChunksCached() {
        long now = System.currentTimeMillis();
        List<Map<String, Object>> hit = cachedScrollRows;
        if (hit != null && now - cachedScrollAtMs < SCROLL_CACHE_TTL_MS) {
            return hit;
        }
        List<Map<String, Object>> rows = scrollAllChunks();
        cachedScrollRows = rows;
        cachedScrollAtMs = now;
        return rows;
    }

    @Override
    public Map<String, Object> listDocuments(int page, int pageSize) {
        int p = Math.max(1, page);
        int size = Math.max(1, Math.min(pageSize, 100));
        try {
            ensureCollection();
        } catch (Exception e) {
            log.warn("[知识库] 集合初始化失败，返回空列表: {}", e.getMessage());
            return Map.of("total", 0, "page", p, "pageSize", size, "list", List.of());
        }
        List<Map<String, Object>> allChunks = scrollAllChunksCached();
        Map<String, Map<String, Object>> grouped = new LinkedHashMap<>();
        for (Map<String, Object> chunk : allChunks) {
            String docId = String.valueOf(chunk.getOrDefault("documentId", ""));
            if (docId.isBlank()) {
                continue;
            }
            Map<String, Object> doc = grouped.computeIfAbsent(docId, id -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("documentId", id);
                m.put("title", chunk.get("title"));
                m.put("category", chunk.get("category"));
                m.put("source", chunk.get("source"));
                m.put("createdAt", chunk.get("createdAt"));
                m.put("chunkCount", 0);
                m.put("preview", "");
                return m;
            });
            int count = (int) doc.get("chunkCount") + 1;
            doc.put("chunkCount", count);
            if (doc.get("preview") == null || String.valueOf(doc.get("preview")).isBlank()) {
                String content = String.valueOf(chunk.getOrDefault("content", ""));
                doc.put("preview", abbreviate(content, 200));
            }
        }
        List<Map<String, Object>> docs = grouped.values().stream()
                .sorted(Comparator.comparing(d -> String.valueOf(d.getOrDefault("createdAt", "")), Comparator.reverseOrder()))
                .collect(Collectors.toList());
        int from = (p - 1) * size;
        int to = Math.min(from + size, docs.size());
        List<Map<String, Object>> pageList = from >= docs.size() ? List.of() : docs.subList(from, to);
        cachedDocumentCount = docs.size();
        documentCountCachedAtMs = System.currentTimeMillis();
        return Map.of(
                "total", docs.size(),
                "page", p,
                "pageSize", size,
                "list", pageList
        );
    }

    private List<Map<String, Object>> scrollAllChunks() {
        List<Map<String, Object>> rows = new ArrayList<>();
        Object offset = null;
        for (int i = 0; i < 50; i++) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("limit", 128);
            body.put("with_payload", true);
            body.put("with_vector", false);
            if (offset != null) {
                body.put("offset", offset);
            }
            JsonNode json = postQuiet("/collections/" + collectionName + "/points/scroll", body);
            if (json == null || !json.has("result")) {
                break;
            }
            JsonNode result = json.path("result");
            JsonNode points = result.path("points");
            if (!points.isArray() || points.isEmpty()) {
                break;
            }
            for (JsonNode point : points) {
                JsonNode payload = point.path("payload");
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", point.path("id").asText());
                row.put("title", payload.path("title").asText(""));
                row.put("content", payload.path("content").asText(""));
                row.put("documentId", payload.path("documentId").asText(""));
                row.put("category", payload.path("category").asText(""));
                row.put("source", payload.path("source").asText(""));
                row.put("createdAt", payload.path("createdAt").asText(""));
                rows.add(row);
            }
            if (!result.has("next_page_offset") || result.path("next_page_offset").isNull()) {
                break;
            }
            offset = objectMapper.convertValue(result.path("next_page_offset"), Object.class);
        }
        return rows;
    }

    private boolean isCollectionPresent() {
        if (Boolean.TRUE.equals(collectionReady)) {
            return true;
        }
        long now = System.currentTimeMillis();
        if (collectionReady != null && now - collectionProbeAtMs < COLLECTION_PROBE_TTL_MS) {
            return Boolean.TRUE.equals(collectionReady);
        }
        JsonNode info = getJson("/collections/" + collectionName);
        boolean present = info != null && info.has("result");
        if (present) {
            collectionReady = true;
        }
        collectionProbeAtMs = now;
        return present;
    }

    private void ensureCollection() {
        if (Boolean.TRUE.equals(collectionReady)) {
            return;
        }
        synchronized (collectionLock) {
            if (Boolean.TRUE.equals(collectionReady)) {
                return;
            }
            int dim = knowledgeProperties.getVectorDimensions();
            JsonNode info = getJson("/collections/" + collectionName);
            if (info != null && info.has("result")) {
                int existing = info.path("result").path("config").path("params").path("vectors").path("size").asInt(-1);
                if (existing > 0 && existing != dim) {
                    log.error("[知识库] 向量维度不匹配（集合={} vs 配置={}），拒绝自动删除集合 {}。"
                                    + "请手工迁移或更换集合名后再启动。",
                            existing, dim, collectionName);
                    collectionReady = false;
                    throw new IllegalStateException("知识库向量维度不匹配（" + existing + " vs " + dim
                            + "），已禁止自动删集合以避免数据丢失");
                } else {
                    collectionReady = true;
                    collectionProbeAtMs = System.currentTimeMillis();
                }
                return;
            }
            createCollectionOnce(dim);
        }
    }

    private void createCollectionOnce(int dim) {
        log.info("[知识库] 创建 Qdrant 集合 {}，维度 {}（进程内仅发起一次创建请求）", collectionName, dim);
        // Qdrant API：创建集合必须 PUT /collections/{name}
        put("/collections/" + collectionName, Map.of(
                "vectors", Map.of("size", dim, "distance", "Cosine")
        ));
        collectionReady = true;
        collectionProbeAtMs = System.currentTimeMillis();
    }

    private void createCollection(int dim) {
        createCollectionOnce(dim);
    }

    private String extractText(MultipartFile file, String ext) {
        try {
            byte[] bytes = file.getBytes();
            return switch (ext) {
                case "md", "markdown" -> readMarkdown(bytes);
                case "pdf" -> readPdf(bytes);
                case "txt", "log", "json", "yml", "yaml", "conf", "cfg" ->
                        new String(bytes, StandardCharsets.UTF_8);
                default -> throw new IllegalArgumentException("不支持的文件类型: " + ext + "（支持 txt/md/pdf/log）");
            };
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("文件解析失败: " + e.getMessage(), e);
        }
    }

    private String readMarkdown(byte[] bytes) throws Exception {
        Path tmp = Files.createTempFile("kb-md-", ".md");
        try {
            Files.write(tmp, bytes);
            MarkdownDocumentReader reader = new MarkdownDocumentReader(tmp.toString());
            List<Document> docs = reader.get();
            return docs.stream().map(Document::getText).collect(Collectors.joining("\n\n"));
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private String readPdf(byte[] bytes) throws Exception {
        Path tmp = Files.createTempFile("kb-pdf-", ".pdf");
        try {
            Files.write(tmp, bytes);
            PagePdfDocumentReader reader = new PagePdfDocumentReader(tmp.toString());
            List<Document> docs = reader.read();
            return docs.stream().map(Document::getText).collect(Collectors.joining("\n\n"));
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private float[] embed(String text) {
        if (embeddingModel != null) {
            try {
                return embeddingModel.embed(text);
            } catch (Exception e) {
                log.warn("[知识库] Embedding API 失败，尝试降级: {}", e.getMessage());
            }
        }
        if (knowledgeProperties.isAllowLocalFallbackEmbedding()) {
            return localFallbackEmbed(text);
        }
        throw new IllegalStateException("Embedding 模型不可用，请配置 AI_API_KEY");
    }

    private String resolveEmbeddingMode() {
        if (embeddingModel != null) {
            return "spring-ai";
        }
        if (knowledgeProperties.isAllowLocalFallbackEmbedding()) {
            return "local-fallback";
        }
        return "none";
    }

    private float[] localFallbackEmbed(String text) {
        int dim = knowledgeProperties.getVectorDimensions();
        float[] vec = new float[dim];
        if (text == null || text.isBlank()) {
            return vec;
        }
        String[] tokens = text.toLowerCase(Locale.ROOT).split("\\s+");
        for (String token : tokens) {
            if (token.isBlank()) {
                continue;
            }
            int i = Math.floorMod(token.hashCode(), dim);
            vec[i] += 1.0f;
        }
        for (int i = 0; i < text.length(); i++) {
            int idx = Math.floorMod(text.charAt(i), dim);
            vec[idx] += 0.05f;
        }
        float norm = 0f;
        for (float v : vec) {
            norm += v * v;
        }
        norm = (float) Math.sqrt(norm);
        if (norm > 0) {
            for (int i = 0; i < vec.length; i++) {
                vec[i] /= norm;
            }
        }
        return vec;
    }

    private void upsertPoints(List<Map<String, Object>> points) {
        // Qdrant API：upsert 必须 PUT /collections/{name}/points（POST 是按 id 读取）
        put("/collections/" + collectionName + "/points?wait=true", Map.of("points", points));
    }

    private List<Double> toDoubleList(float[] arr) {
        List<Double> list = new ArrayList<>(arr.length);
        for (float v : arr) {
            list.add((double) v);
        }
        return list;
    }

    private HttpHeaders qdrantHeaders(boolean json) {
        HttpHeaders headers = new HttpHeaders();
        if (json) {
            headers.setContentType(MediaType.APPLICATION_JSON);
        }
        if (qdrantApiKey != null && !qdrantApiKey.isBlank()) {
            headers.set("api-key", qdrantApiKey.trim());
        }
        return headers;
    }

    private static String normalizeQdrantUrl(String raw) {
        String u = raw == null || raw.isBlank() ? "http://localhost:6333" : raw.trim();
        while (u.endsWith("/")) {
            u = u.substring(0, u.length() - 1);
        }
        // 本地 Docker 常见无协议数字端口；Cloud HTTPS 走 443，勿强加 :6333
        if (u.contains("cloud.qdrant.io") && u.startsWith("http://") && !u.matches(".*:\\d+$")) {
            u = u + ":6333";
        }
        return u;
    }

    private JsonNode getJson(String path) {
        try {
            HttpEntity<Void> entity = new HttpEntity<>(qdrantHeaders(false));
            ResponseEntity<String> response = restTemplate.exchange(
                    qdrantUrl + path, HttpMethod.GET, entity, String.class);
            return objectMapper.readTree(response.getBody());
        } catch (Exception e) {
            return null;
        }
    }

    private void deleteJson(String path) {
        HttpEntity<Void> entity = new HttpEntity<>(qdrantHeaders(false));
        restTemplate.exchange(qdrantUrl + path, HttpMethod.DELETE, entity, String.class);
    }

    private JsonNode postQuiet(String path, Map<String, Object> body) {
        try {
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, qdrantHeaders(true));
            ResponseEntity<String> response = restTemplate.postForEntity(qdrantUrl + path, entity, String.class);
            return objectMapper.readTree(response.getBody());
        } catch (Exception e) {
            log.debug("[知识库] Qdrant POST {} 失败: {}", path, e.getMessage());
            return null;
        }
    }

    private JsonNode post(String path, Map<String, Object> body) {
        try {
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, qdrantHeaders(true));
            ResponseEntity<String> response = restTemplate.postForEntity(qdrantUrl + path, entity, String.class);
            return objectMapper.readTree(response.getBody());
        } catch (Exception e) {
            log.warn("[知识库] Qdrant 请求失败 {}: {}", path, e.getMessage());
            throw new IllegalStateException("向量库不可用，请确认 Qdrant 地址与 API Key: " + e.getMessage(), e);
        }
    }

    private JsonNode put(String path, Map<String, Object> body) {
        try {
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, qdrantHeaders(true));
            ResponseEntity<String> response = restTemplate.exchange(
                    qdrantUrl + path, HttpMethod.PUT, entity, String.class);
            if (response.getBody() == null || response.getBody().isBlank()) {
                return objectMapper.createObjectNode();
            }
            return objectMapper.readTree(response.getBody());
        } catch (Exception e) {
            log.warn("[知识库] Qdrant PUT 失败 {}: {}", path, e.getMessage());
            throw new IllegalStateException("向量库不可用，请确认 Qdrant 地址与 API Key: " + e.getMessage(), e);
        }
    }

    private static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String abbreviate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
