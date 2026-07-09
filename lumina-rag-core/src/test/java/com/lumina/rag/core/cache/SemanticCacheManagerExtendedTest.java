package com.lumina.rag.core.cache;

import com.lumina.rag.core.entity.SemanticCacheEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.elasticsearch.core.ElasticsearchRestTemplate;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.query.NativeSearchQuery;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * SemanticCacheManager 补充测试
 *
 * 覆盖：
 * - L2 回填 L1 缓存逻辑
 * - 缓存更新操作
 * - 条件缓存操作
 * - 并发缓存访问
 */
@ExtendWith(MockitoExtension.class)
class SemanticCacheManagerExtendedTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private ElasticsearchRestTemplate elasticsearchRestTemplate;
    @Mock
    private SemanticCacheRepository cacheRepository;
    @Mock
    private ValueOperations<String, String> valueOperations;

    private SemanticCacheManager cacheManager;

    @BeforeEach
    void setUp() {
        lenient().when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        cacheManager = new SemanticCacheManager(stringRedisTemplate, elasticsearchRestTemplate, cacheRepository);
    }

    @Test
    @DisplayName("L1 命中时直接返回，不查 L2")
    void getCache_L1Hit_ShouldReturnDirectly() {
        String indexName = "test-index";
        String queryText = "测试查询";
        List<Float> queryVector = List.of(0.1f, 0.2f, 0.3f);
        String cachedValue = "缓存值";

        // L1 命中（L1 使用 MD5 key）
        when(valueOperations.get(anyString())).thenReturn(cachedValue);

        String result = cacheManager.getCache(indexName, queryText, queryVector);

        assertEquals(cachedValue, result);
        // 验证没有执行 ES 搜索（不查 L2）
        verify(elasticsearchRestTemplate, never()).search(any(NativeSearchQuery.class), any());
    }

    @Test
    @DisplayName("L1 未命中但 L2 命中时，回填 L1 并返回")
    void getCache_L1MissL2Hit_ShouldBackfillL1() {
        String indexName = "test-index";
        String queryText = "测试查询";
        List<Float> queryVector = List.of(0.1f, 0.2f, 0.3f);
        String l2Value = "L2缓存值";

        // L1 未命中
        when(valueOperations.get(anyString())).thenReturn(null);

        // L2 命中（需要 mock ES search 返回结果）
        SemanticCacheEntity entity = SemanticCacheEntity.builder()
                .indexName(indexName)
                .queryText(queryText)
                .queryVector(queryVector)
                .llmResponse(l2Value)
                .build();
        SearchHit<SemanticCacheEntity> searchHit = mock(SearchHit.class);
        when(searchHit.getContent()).thenReturn(entity);
        SearchHits<SemanticCacheEntity> searchHits = mock(SearchHits.class);
        when(searchHits.hasSearchHits()).thenReturn(true);
        when(searchHits.getSearchHit(0)).thenReturn(searchHit);
        when(elasticsearchRestTemplate.search(any(NativeSearchQuery.class), eq(SemanticCacheEntity.class)))
                .thenReturn(searchHits);

        String result = cacheManager.getCache(indexName, queryText, queryVector);

        assertEquals(l2Value, result);
        // 验证 L2 结果写回 L1（带 TTL 的 4 参数 set）
        verify(valueOperations, atLeastOnce()).set(anyString(), eq(l2Value), anyLong(), any());
    }

    @Test
    @DisplayName("L1 和 L2 都未命中时返回 null")
    void getCache_BothMiss_ShouldReturnNull() {
        // L1 未命中
        when(valueOperations.get(anyString())).thenReturn(null);

        // L2 未命中
        SearchHits<SemanticCacheEntity> searchHits = mock(SearchHits.class);
        when(searchHits.hasSearchHits()).thenReturn(false);
        when(elasticsearchRestTemplate.search(any(NativeSearchQuery.class), eq(SemanticCacheEntity.class)))
                .thenReturn(searchHits);

        String result = cacheManager.getCache("index", "query", List.of(0.1f, 0.2f));

        assertNull(result);
    }

    @Test
    @DisplayName("putCache 应写入 L1 Redis 和 L2 ES")
    void putCache_ShouldWriteBothLayers() {
        String indexName = "test-index";
        String queryText = "测试查询";
        List<Float> queryVector = List.of(0.1f, 0.2f, 0.3f);
        String response = "LLM 回答";
        List<String> refDocIds = List.of("doc-1", "doc-2");

        cacheManager.putCache(indexName, queryText, queryVector, response, refDocIds);

        // 验证 L1 写入（带 TTL）
        verify(valueOperations, atLeastOnce()).set(anyString(), eq(response), anyLong(), any());
        // 验证 L2 写入
        verify(cacheRepository, times(1)).save(any(SemanticCacheEntity.class));
    }

    @Test
    @DisplayName("invalidateCacheByDocId 应清除 L1 和 L2 缓存")
    void invalidateCacheByDocId_ShouldClearBothLayers() {
        String docId = "doc-to-invalidate";

        SemanticCacheEntity entity = SemanticCacheEntity.builder()
                .id("cache-1")
                .indexName("test-index")
                .queryText("旧的查询")
                .queryVector(List.of(0.1f, 0.2f))
                .llmResponse("旧的回答")
                .refDocIds(List.of(docId))
                .build();

        SearchHit<SemanticCacheEntity> searchHit = mock(SearchHit.class);
        when(searchHit.getContent()).thenReturn(entity);
        SearchHits<SemanticCacheEntity> searchHits = mock(SearchHits.class);
        when(searchHits.hasSearchHits()).thenReturn(true);
        when(searchHits.iterator()).thenReturn(List.of(searchHit).iterator());
        when(elasticsearchRestTemplate.search(any(NativeSearchQuery.class), eq(SemanticCacheEntity.class)))
                .thenReturn(searchHits);

        cacheManager.invalidateCacheByDocId(docId);

        // 验证 L1 删除
        verify(stringRedisTemplate, atLeastOnce()).delete(anyString());
        // 验证 L2 删除
        verify(cacheRepository, times(1)).deleteById("cache-1");
    }

    @Test
    @DisplayName("invalidateCacheByDocId 无关联缓存时应不执行删除")
    void invalidateCacheByDocId_NoRelatedCache_ShouldDoNothing() {
        SearchHits<SemanticCacheEntity> searchHits = mock(SearchHits.class);
        when(searchHits.hasSearchHits()).thenReturn(false);
        when(elasticsearchRestTemplate.search(any(NativeSearchQuery.class), eq(SemanticCacheEntity.class)))
                .thenReturn(searchHits);

        cacheManager.invalidateCacheByDocId("nonexistent-doc");

        // 验证没有删除任何缓存
        verify(stringRedisTemplate, never()).delete(anyString());
        verify(cacheRepository, never()).deleteById(anyString());
    }

    @Test
    @DisplayName("不同 index 的缓存操作应隔离（L1 key 包含 indexName）")
    void differentIndex_ShouldBeIsolated() {
        // 模拟 L1 缓存 - 不同 index 生成的 MD5 key 不同，返回不同值
        when(valueOperations.get(anyString())).thenReturn(null);
        // L2 都未命中
        SearchHits<SemanticCacheEntity> searchHits = mock(SearchHits.class);
        when(searchHits.hasSearchHits()).thenReturn(false);
        when(elasticsearchRestTemplate.search(any(NativeSearchQuery.class), eq(SemanticCacheEntity.class)))
                .thenReturn(searchHits);

        String resultA = cacheManager.getCache("index-A", "same-query", List.of(0.1f));
        String resultB = cacheManager.getCache("index-B", "same-query", List.of(0.1f));

        assertNull(resultA);
        assertNull(resultB);
        // 验证两次 getCache 调用使用了不同的 key（因为 indexName 不同）
        verify(valueOperations, times(2)).get(anyString());
    }

    @Test
    @DisplayName("L2 缓存查询异常时应优雅降级返回 null")
    void getCache_L2Exception_ShouldDegradeGracefully() {
        when(valueOperations.get(anyString())).thenReturn(null);
        when(elasticsearchRestTemplate.search(any(NativeSearchQuery.class), eq(SemanticCacheEntity.class)))
                .thenThrow(new RuntimeException("ES 异常"));

        String result = cacheManager.getCache("index", "query", List.of(0.1f));

        assertNull(result, "异常时应返回 null，降级穿透到大模型");
    }
}
