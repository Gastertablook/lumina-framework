package com.lumina.rag.core.spi;

import com.lumina.rag.core.domain.DocumentChunk;
import java.util.List;
import java.util.Map;

/**
 * 向量存储与混合检索核心接口
 * 这是驾驭 AI "记忆" 的底层接口。
 */
public interface VectorStoreService {

    /**
     * 1. 存入：批量保存切块（写入 ES 索引）
     */
    void saveChunks(String indexName, List<DocumentChunk> chunks);

    /**
     * 2. 查：终极混合检索 (Hybrid Search)
     * @param indexName 目标索引
     * @param queryText 用于词法防线的用户提问 (过 IK 分词)
     * @param queryVector 用于语义算分的用户提问向量
     * @param filterConditions 元数据硬过滤条件 (如必须属于某个 brandId 或某个 parentId)
     * @param topK 召回数量
     */
    List<DocumentChunk> hybridSearch(
            String indexName,
            String queryText,
            List<Float> queryVector,
            Map<String, Object> filterConditions,
            int topK
    );

    // 根据父文档 ID 删除所有底层碎片
    void deleteChunksByParentId(String indexName, String parentId);
}