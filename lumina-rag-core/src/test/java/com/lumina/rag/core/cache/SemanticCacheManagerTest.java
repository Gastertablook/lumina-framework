package com.lumina.rag.core.cache;

import com.lumina.rag.core.constant.LuminaConstants;
import com.lumina.rag.core.entity.SemanticCacheEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.elasticsearch.core.ElasticsearchRestTemplate;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.data.elasticsearch.core.query.NativeSearchQuery;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 【驾驭层】多级语义缓存全方位测试
 *
 * 测试覆盖：
 * 1. L1 Redis 缓存命中/未命中
 * 2. L2 ES 语义缓存命中/未命中
 * 3. 缓存写入 L1+L2 双写一致性
 * 4. 缓存 GC (通过 docId 炸毁相关缓存)
 * 5. 缓存未命中降级
 * 6. L2 → L1 回写加速
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SemanticCacheManager 多级语义缓存测试")
class SemanticCacheManagerTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ElasticsearchRestTemplate elasticsearchRestTemplate;

    @Mock
    private SemanticCacheRepository semanticCacheRepository;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Captor
    private ArgumentCaptor<String> l1KeyCaptor;

    @Captor
    private ArgumentCaptor<String> l1ValueCaptor;

    private SemanticCacheManager cacheManager;
    private final String indexName = "test_index";
    private final String queryText = "什么是Java内存模型";
    private final List<Float> queryVector = List.of(0.1f, 0.2f, 0.3f, /* ...384维... */ 0.4f);

    @BeforeEach
    void setUp() {
        lenient().when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        cacheManager = new SemanticCacheManager(stringRedisTemplate, elasticsearchRestTemplate, semanticCacheRepository);
    }

    // ==================== 1. L1 Redis 缓存测试 ====================

    @Test
    @DisplayName("L1 Redis 缓存命中时直接返回，不查 L2")
    void l1CacheHit_shouldReturnDirectly() {
        String expectedResponse = "Java内存模型是JVM规范...";
        String l1Key = LuminaConstants.L1_CACHE_PREFIX +
                org.springframework.util.DigestUtils.md5DigestAsHex(
                        (indexName + ":" + queryText).getBytes(java.nio.charset.StandardCharsets.UTF_8));

        when(valueOperations.get(l1Key)).thenReturn(expectedResponse);

        String result = cacheManager.getCache(indexName, queryText, queryVector);

        assertEquals(expectedResponse, result, "L1 命中时应直接返回缓存结果");
        // 验证没有调用 ES (L2)
        verify(elasticsearchRestTemplate, never()).search(any(NativeSearchQuery.class), any());
    }

    @Test
    @DisplayName("L1 缓存未命中，queryVector 为 null 时应返回 null")
    void l1Miss_nullVector_shouldReturnNull() {
        String l1Key = LuminaConstants.L1_CACHE_PREFIX +
                org.springframework.util.DigestUtils.md5DigestAsHex(
                        (indexName + ":" + queryText).getBytes(java.nio.charset.StandardCharsets.UTF_8));

        when(valueOperations.get(l1Key)).thenReturn(null);

        String result = cacheManager.getCache(indexName, queryText, null);

        assertNull(result, "L1 未命中且无向量时应该返回 null");
    }

    // ==================== 2. L1+L2 全链路缓存测试 ====================

    @Test
    @DisplayName("L1 未命中且 L2 命中时，应返回 L2 结果并回写 L1")
    void l2CacheHit_shouldReturnAndWriteBackToL1() {
        String l2Response = "L2 语义缓存命中结果...";

        // L1 未命中
        String l1Key = LuminaConstants.L1_CACHE_PREFIX +
                org.springframework.util.DigestUtils.md5DigestAsHex(
                        (indexName + ":" + queryText).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        when(valueOperations.get(l1Key)).thenReturn(null);

        // L2 命中（需要 mock SearchHits）
        @SuppressWarnings("unchecked")
        SearchHit<SemanticCacheEntity> searchHit = mock(SearchHit.class);
        SemanticCacheEntity cacheEntity = SemanticCacheEntity.builder()
                .llmResponse(l2Response)
                .build();
        when(searchHit.getContent()).thenReturn(cacheEntity);

        @SuppressWarnings("unchecked")
        SearchHits<SemanticCacheEntity> searchHits = mock(SearchHits.class);
        when(searchHits.hasSearchHits()).thenReturn(true);
        when(searchHits.getSearchHit(0)).thenReturn(searchHit);

        when(elasticsearchRestTemplate.search(any(NativeSearchQuery.class), eq(SemanticCacheEntity.class)))
                .thenReturn(searchHits);

        String result = cacheManager.getCache(indexName, queryText, queryVector);

        assertEquals(l2Response, result, "L2 命中时应返回 L2 缓存结果");

        // 验证 L2 → L1 回写
        verify(valueOperations).set(l1KeyCaptor.capture(), l1ValueCaptor.capture(),
                eq(24L), eq(TimeUnit.HOURS));
        assertEquals(l2Response, l1ValueCaptor.getValue(),
                "L2 命中的结果应该回写到 L1，加速下次访问");
    }

    // ==================== 3. 全缓存未命中 ====================

    @Test
    @DisplayName("L1 和 L2 都未命中时，返回 null（触发大模型调用）")
    void allCacheMiss_shouldReturnNull() {
        // L1 未命中
        String l1Key = LuminaConstants.L1_CACHE_PREFIX +
                org.springframework.util.DigestUtils.md5DigestAsHex(
                        (indexName + ":" + queryText).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        when(valueOperations.get(l1Key)).thenReturn(null);

        // L2 未命中
        @SuppressWarnings("unchecked")
        SearchHits<SemanticCacheEntity> searchHits = mock(SearchHits.class);
        when(searchHits.hasSearchHits()).thenReturn(false);
        when(elasticsearchRestTemplate.search(any(NativeSearchQuery.class), eq(SemanticCacheEntity.class)))
                .thenReturn(searchHits);

        String result = cacheManager.getCache(indexName, queryText, queryVector);

        assertNull(result, "所有缓存未命中时应返回 null，触发下游大模型调用");
    }

    // ==================== 4. 缓存写入测试 ====================

    @Test
    @DisplayName("写入缓存时应该同时写入 L1 Redis 和 L2 ES")
    void putCache_shouldWriteToBothLayers() {
        String response = "大模型回答内容...";
        List<String> refDocIds = List.of("doc_111", "doc_222");

        cacheManager.putCache(indexName, queryText, queryVector, response, refDocIds);

        // 验证 L1 写入
        verify(valueOperations).set(anyString(), eq(response), eq(24L), eq(TimeUnit.HOURS));

        // 验证 L2 写入（save 到 ES）
        verify(semanticCacheRepository).save(any(SemanticCacheEntity.class));
    }

    @Test
    @DisplayName("写入缓存时，L2 实体应包含正确的血缘关系 refDocIds")
    void putCache_shouldContainRefDocIds() {
        String response = "测试回答";
        List<String> refDocIds = List.of("doc_abc123");

        cacheManager.putCache(indexName, queryText, queryVector, response, refDocIds);

        ArgumentCaptor<SemanticCacheEntity> entityCaptor = ArgumentCaptor.forClass(SemanticCacheEntity.class);
        verify(semanticCacheRepository).save(entityCaptor.capture());

        SemanticCacheEntity saved = entityCaptor.getValue();
        assertEquals(indexName, saved.getIndexName());
        assertEquals(queryText, saved.getQueryText());
        assertEquals(refDocIds, saved.getRefDocIds());
        assertEquals(response, saved.getLlmResponse());
        assertNotNull(saved.getCreateTime());
    }

    // ==================== 5. 缓存 GC 测试 ====================

    @Test
    @DisplayName("缓存 GC：根据 docId 炸毁相关的 L1 和 L2 缓存")
    void invalidateCacheByDocId_shouldDestroyRelatedCaches() {
        String docId = "doc_to_delete";
        String cachedQuery = "被缓存的问题";
        String cachedIndex = "some_index";

        // 模拟 ES 查到相关缓存记录
        SemanticCacheEntity entity = SemanticCacheEntity.builder()
                .id("cache_001")
                .indexName(cachedIndex)
                .queryText(cachedQuery)
                .llmResponse("旧的缓存回答")
                .refDocIds(List.of(docId))
                .build();

        @SuppressWarnings("unchecked")
        SearchHit<SemanticCacheEntity> searchHit = mock(SearchHit.class);
        when(searchHit.getContent()).thenReturn(entity);

        @SuppressWarnings("unchecked")
        SearchHits<SemanticCacheEntity> searchHits = mock(SearchHits.class);
        when(searchHits.hasSearchHits()).thenReturn(true);
        when(searchHits.iterator()).thenReturn(List.of(searchHit).iterator());

        when(elasticsearchRestTemplate.search(any(NativeSearchQuery.class), eq(SemanticCacheEntity.class)))
                .thenReturn(searchHits);

        // 执行 GC
        cacheManager.invalidateCacheByDocId(docId);

        // 验证 L1 被删除
        String expectedL1Key = LuminaConstants.L1_CACHE_PREFIX +
                org.springframework.util.DigestUtils.md5DigestAsHex(
                        (cachedIndex + ":" + cachedQuery).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        verify(stringRedisTemplate).delete(expectedL1Key);

        // 验证 L2 被删除
        verify(semanticCacheRepository).deleteById("cache_001");
    }

    @Test
    @DisplayName("缓存 GC：无关联缓存时不做任何操作")
    void invalidateCacheByDocId_noRelatedCache_shouldDoNothing() {
        @SuppressWarnings("unchecked")
        SearchHits<SemanticCacheEntity> searchHits = mock(SearchHits.class);
        when(searchHits.hasSearchHits()).thenReturn(false);
        when(elasticsearchRestTemplate.search(any(NativeSearchQuery.class), eq(SemanticCacheEntity.class)))
                .thenReturn(searchHits);

        cacheManager.invalidateCacheByDocId("nonexistent_doc");

        // 验证没有发生删除操作
        verify(stringRedisTemplate, never()).delete(anyString());
        verify(semanticCacheRepository, never()).deleteById(anyString());
    }

    // ==================== 6. L2 ES 异常降级 ====================

    @Test
    @DisplayName("L2 ES 查询异常时应优雅降级，返回 null 而非抛异常")
    void l2Exception_shouldDegradeGracefully() {
        // L1 未命中
        String l1Key = LuminaConstants.L1_CACHE_PREFIX +
                org.springframework.util.DigestUtils.md5DigestAsHex(
                        (indexName + ":" + queryText).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        when(valueOperations.get(l1Key)).thenReturn(null);

        // L2 查询抛异常
        when(elasticsearchRestTemplate.search(any(NativeSearchQuery.class), eq(SemanticCacheEntity.class)))
                .thenThrow(new RuntimeException("ES 连接超时"));

        // 不应该抛出异常
        String result = cacheManager.getCache(indexName, queryText, queryVector);

        assertNull(result, "L2 异常时应降级返回 null，不能向上抛异常");
    }

    // ==================== 7. 不同 indexName 缓存隔离 ====================

    @Test
    @DisplayName("不同 indexName 的缓存应该完全隔离")
    void differentIndexName_shouldIsolate() {
        String indexA = "tenant_a_workspace";
        String indexB = "tenant_b_workspace";

        String l1KeyA = LuminaConstants.L1_CACHE_PREFIX +
                org.springframework.util.DigestUtils.md5DigestAsHex(
                        (indexA + ":" + queryText).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String l1KeyB = LuminaConstants.L1_CACHE_PREFIX +
                org.springframework.util.DigestUtils.md5DigestAsHex(
                        (indexB + ":" + queryText).getBytes(java.nio.charset.StandardCharsets.UTF_8));

        when(valueOperations.get(l1KeyA)).thenReturn("租户A的回答");
        when(valueOperations.get(l1KeyB)).thenReturn(null);

        // 租户A 命中缓存
        String resultA = cacheManager.getCache(indexA, queryText, queryVector);
        assertEquals("租户A的回答", resultA);

        // 租户B 未命中
        @SuppressWarnings("unchecked")
        SearchHits<SemanticCacheEntity> searchHits = mock(SearchHits.class);
        when(searchHits.hasSearchHits()).thenReturn(false);
        when(elasticsearchRestTemplate.search(any(NativeSearchQuery.class), eq(SemanticCacheEntity.class)))
                .thenReturn(searchHits);

        String resultB = cacheManager.getCache(indexB, queryText, queryVector);
        assertNull(resultB, "不同租户的缓存必须隔离");
    }
}
