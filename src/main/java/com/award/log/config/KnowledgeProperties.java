package com.award.log.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "knowledge")
public class KnowledgeProperties {

    /** 是否启用知识库 RAG */
    private boolean enabled = true;

    /** 单块最大字符数 */
    private int chunkSize = 900;

    /** 块间重叠字符数 */
    private int chunkOverlap = 150;

    /** 对话检索 Top-K */
    private int searchTopK = 5;

    /** 向量维度（须与 embedding 模型一致） */
    private int vectorDimensions = 1024;

    /** 无 Embedding API 时是否降级为本地哈希向量 */
    private boolean allowLocalFallbackEmbedding = true;

    /** 启动时若知识库为空则写入内置 Runbook */
    private boolean seedOnStartup = true;
}
