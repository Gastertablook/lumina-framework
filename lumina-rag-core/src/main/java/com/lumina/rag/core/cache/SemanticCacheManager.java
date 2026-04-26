package com.lumina.rag.core.cache;

import com.lumina.rag.core.constant.LuminaConstants;
import com.lumina.rag.core.entity.SemanticCacheEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.elasticsearch.index.query.BoolQueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.index.query.functionscore.ScriptScoreFunctionBuilder;
import org.elasticsearch.script.Script;
import org.elasticsearch.script.ScriptType;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.elasticsearch.core.ElasticsearchRestTemplate;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.data.elasticsearch.core.query.NativeSearchQuery;
import org.springframework.data.elasticsearch.core.query.NativeSearchQueryBuilder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.DigestUtils;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 驾驭层：多级语义缓存管理器 (L1 Redis + L2 ES)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SemanticCacheManager {

    private final StringRedisTemplate stringRedisTemplate;
    private final ElasticsearchRestTemplate elasticsearchRestTemplate;
    private final SemanticCacheRepository semanticCacheRepository;

    // 相似度阈值：向量余弦相似度必须大于 0.85 才能命中 L2 缓存 (防幻觉)
    private static final double SIMILARITY_THRESHOLD = 0.85;
    private static final String MINIMUM_SHOULD_MATCH = "60%";

    /**
     * 核心操作 1：获取缓存 (按照 L1 -> L2 的顺序)
     */
    public String getCache(String indexName, String queryText, List<Float> queryVector) {
        // 1. L1 Redis 缓存 (精准匹配，防爆刷)
        String md5Key = LuminaConstants.L1_CACHE_PREFIX + DigestUtils.md5DigestAsHex((indexName + ":" + queryText).getBytes(StandardCharsets.UTF_8));
        String l1Result = stringRedisTemplate.opsForValue().get(md5Key);
        if (l1Result != null) {
            log.info("L1 Redis Cache 命中! 耗时约 7ms. Query: {}", queryText);
            return l1Result;
        }

        // 2. L2 ES 语义缓存 (混合检索：IK 分词兜底 + 向量算分)
        if (queryVector != null && !queryVector.isEmpty()) {
            String l2Result = checkL2SemanticCache(indexName, queryText, queryVector);
            if (l2Result != null) {
                log.info("L2 ES Semantic Cache 命中! 耗时约 50ms. Query: {}", queryText);
                // 驾驭升维：将 L2 命中的结果写回 L1，让下一次相同的提问加速！
                stringRedisTemplate.opsForValue().set(md5Key, l2Result, 24, TimeUnit.HOURS);
                return l2Result;
            }
        }

        return null; // 彻底未命中，准备调用大模型
    }

    /**
     * 核心操作 2：保存缓存 (回答生成后，写入 L1 和 L2)
     */
    public void putCache(String indexName, String queryText, List<Float> queryVector, String llmResponse, List<String> refDocIds) {
        // 写入 L1
        String md5Key = LuminaConstants.L1_CACHE_PREFIX + DigestUtils.md5DigestAsHex((indexName + ":" + queryText).getBytes(StandardCharsets.UTF_8));
        stringRedisTemplate.opsForValue().set(md5Key, llmResponse, 24, TimeUnit.HOURS);

        // 写入 L2
        SemanticCacheEntity entity = SemanticCacheEntity.builder()
                .indexName(indexName)
                .queryText(queryText)
                .queryVector(queryVector)
                .llmResponse(llmResponse)
                .refDocIds(refDocIds) // 记录血缘关系，等待 Kafka 清理
                .createTime(System.currentTimeMillis())
                .build();
        semanticCacheRepository.save(entity);
        log.info("已存入 L1 & L2 多级缓存，血缘关联 Docs: {}", refDocIds);
    }

    /**
     * 内部方法：执行 ES 混合检索查询 L2 缓存
     */
    private String checkL2SemanticCache(String indexName, String queryText, List<Float> queryVector) {
        try {
            // 构建词法防线 (IK分词，至少要包含核心词，防止向量过度联想)
            BoolQueryBuilder boolQuery = QueryBuilders.boolQuery();
            boolQuery.must(QueryBuilders.matchQuery(LuminaConstants.FIELD_QUERY_TEXT, queryText)
                    .minimumShouldMatch(MINIMUM_SHOULD_MATCH)); // 驾驭约束：分词重合度 > 60%
            // L2 查询时强制过滤 indexName
            boolQuery.filter(QueryBuilders.termQuery(LuminaConstants.FIELD_INDEX_NAME, indexName));

            // 构建语义算分 (Painless Script)
            Map<String, Object> params = Collections.singletonMap("query_vector", queryVector);
            Script script = new Script(ScriptType.INLINE, "painless", "cosineSimilarity(params.query_vector, 'queryVector') + 1.0", params);
            ScriptScoreFunctionBuilder scriptScoreFunctionBuilder = new ScriptScoreFunctionBuilder(script);

            NativeSearchQuery searchQuery = new NativeSearchQueryBuilder()
                    .withQuery(QueryBuilders.functionScoreQuery(boolQuery, scriptScoreFunctionBuilder))
                    .withMinScore((float) SIMILARITY_THRESHOLD) // 驾驭红线：强制要求相似度达标
                    .withPageable(PageRequest.of(0, 1)) // 只取 Top 1
                    .build();

            SearchHits<SemanticCacheEntity> searchHits = elasticsearchRestTemplate.search(searchQuery, SemanticCacheEntity.class);
            if (searchHits.hasSearchHits()) {
                SearchHit<SemanticCacheEntity> hit = searchHits.getSearchHit(0);
                return hit.getContent().getLlmResponse();
            }
        } catch (Exception e) {
            log.error("L2 缓存查询异常，降级为穿透大模型", e);
        }
        return null;
    }

    /**
     * 【驾驭工程：缓存垃圾回收 (GC)】
     * 当外部业务系统（如知识库文档更新、电商商品变价）触发事件时，调用此方法。
     * 根据关联的 docId 顺藤摸瓜，精准炸毁对应的 L1 和 L2 缓存。
     *
     * @param docId 发生变更的源文档 ID / 商品 ID
     */
    public void invalidateCacheByDocId(String docId) {
        log.info("接收到数据变更事件，准备执行缓存 GC 清理，变更目标 ID: {}", docId);

        try {
            // 1. 去 ES 中查找所有 refDocIds 包含了该 docId 的 L2 缓存记录
            NativeSearchQuery searchQuery = new NativeSearchQueryBuilder()
                    .withQuery(QueryBuilders.termQuery("refDocIds", docId))
                    .build();

            SearchHits<SemanticCacheEntity> searchHits = elasticsearchRestTemplate.search(searchQuery, SemanticCacheEntity.class);

            if (!searchHits.hasSearchHits()) {
                log.info("未找到依赖此 ID 的相关 AI 缓存，无需清理。");
                return;
            }

            // 2. 遍历命中的旧缓存，物理炸毁
            for (SearchHit<SemanticCacheEntity> hit : searchHits) {
                SemanticCacheEntity entity = hit.getContent();

                // 炸毁 L1 Redis 缓存 (必须根据 queryText 重新计算 MD5 才能找到 Key)
                String queryText = entity.getQueryText();
                String indexName = entity.getIndexName();
                String md5Key = LuminaConstants.L1_CACHE_PREFIX + DigestUtils.md5DigestAsHex((indexName + ":" + queryText).getBytes(StandardCharsets.UTF_8));
                Boolean deleted = stringRedisTemplate.delete(md5Key);
                log.info("已物理炸毁 L1 Redis 缓存, Key: {}, 状态: {}", md5Key, deleted);

                // 炸毁 L2 ES 缓存
                semanticCacheRepository.deleteById(entity.getId());
                log.info("已物理炸毁 L2 ES 缓存, CacheID: {}", entity.getId());
            }

            log.info("缓存 GC 清理完成，受影响的缓存条数: {}", searchHits.getTotalHits());

        } catch (Exception e) {
            log.error("缓存 GC 清理失败", e);
        }
    }
}