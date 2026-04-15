package com.lumina.rag.core.cache;

import com.lumina.rag.core.entity.SemanticCacheEntity;
import org.springframework.data.elasticsearch.repository.ElasticsearchRepository;
import org.springframework.stereotype.Repository;

/**
 * 驾驭层：大模型 L2 语义缓存的底层数据访问
 */
@Repository
public interface SemanticCacheRepository extends ElasticsearchRepository<SemanticCacheEntity, String> {
    // 基础的 CRUD 已经由 Spring Data ES 提供
    // 复杂的混合查询我们会在 Manager 里用 ElasticsearchRestTemplate 手写
}