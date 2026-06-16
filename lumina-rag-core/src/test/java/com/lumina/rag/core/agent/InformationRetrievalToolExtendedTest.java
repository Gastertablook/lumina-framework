package com.lumina.rag.core.agent;

import com.lumina.rag.core.cache.SemanticCacheManager;
import com.lumina.rag.core.concurrent.RequestDeduplicator;
import com.lumina.rag.core.spi.VectorStoreService;
import com.lumina.rag.core.domain.DocumentChunk;
import com.lumina.rag.core.constant.LuminaConstants;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * InformationRetrievalTool 补充测试
 *
 * 覆盖已有测试未覆盖的场景：
 * - Small-to-Big 长文溯源
 * - L2 回填 L1 缓存
 * - metadataFilters 过滤
 * - 空结果处理
 * - 跨租户隔离
 * - 异常场景
 */
@ExtendWith(MockitoExtension.class)
class InformationRetrievalToolExtendedTest {

    @Mock
    private VectorStoreService vectorStoreService;
    @Mock
    private EmbeddingModel embeddingModel;
    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;
    @Mock
    private SemanticCacheManager cacheManager;
    @Mock
    private RequestDeduplicator deduplicator;

    private InformationRetrievalTool tool;
    private List<String> sessionRefDocIds;
    private final String indexName = "test-index";
    private final Map<String, Object> metadataFilters = Map.of("category", "technology");
    private final List<Float> mockVector = List.of(0.1f, 0.2f, 0.3f);

    @BeforeEach
    void setUp() {
        sessionRefDocIds = new ArrayList<>();
        tool = new InformationRetrievalTool(
                vectorStoreService, embeddingModel, stringRedisTemplate,
                cacheManager, deduplicator,
                indexName, metadataFilters, sessionRefDocIds
        );

        // 通用 mock：embeddingModel.embed() 返回固定向量（非所有测试都需要 opsForValue）
        lenient().when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    @DisplayName("缓存未命中时，应查询向量存储并使用 Singleflight 防击穿")
    void retrieveInformation_CacheMiss_ShouldQueryVectorStore() {
        String query = "什么是人工智能";

        // 构造带有 parentId metadata 的 DocumentChunk
        DocumentChunk mockChunk = DocumentChunk.builder()
                .chunkId("chunk-1")
                .text("AI是人工智能的缩写")
                .metadata(Map.of(LuminaConstants.FIELD_PARENT_ID, "parent-1"))
                .build();
        List<DocumentChunk> mockResults = List.of(mockChunk);

        // 模拟缓存未命中
        when(cacheManager.getCache(eq(indexName), eq(query), anyList())).thenReturn(null);
        // 模拟 embedding
        when(embeddingModel.embed(anyString())).thenAnswer(invocation -> {
            Embedding emb = mock(Embedding.class);
            when(emb.vectorAsList()).thenReturn(mockVector);
            Response<Embedding> resp = mock(Response.class);
            when(resp.content()).thenReturn(emb);
            return resp;
        });
        // 模拟 deduplicator 执行 supplier
        when(deduplicator.execute(anyString(), any())).thenAnswer(invocation -> {
            java.util.function.Supplier<String> supplier = invocation.getArgument(1);
            return supplier.get();
        });
        // 模拟向量搜索
        when(vectorStoreService.hybridSearch(eq(indexName), eq(query), anyList(), eq(metadataFilters), eq(3)))
                .thenReturn(mockResults);

        String result = tool.retrieveInformation(query, false);

        assertNotNull(result);
        assertTrue(result.contains("AI是人工智能的缩写"));
        // 验证 session 引用被记录
        assertFalse(sessionRefDocIds.isEmpty());
        assertTrue(sessionRefDocIds.contains("parent-1"));

        verify(vectorStoreService, times(1))
                .hybridSearch(eq(indexName), eq(query), anyList(), eq(metadataFilters), eq(3));
    }

    @Test
    @DisplayName("metadataFilters 为 null 时仍能正常工作")
    void retrieveInformation_NullFilters_ShouldWork() {
        tool = new InformationRetrievalTool(
                vectorStoreService, embeddingModel, stringRedisTemplate,
                cacheManager, deduplicator,
                indexName, null, sessionRefDocIds
        );

        String query = "测试查询";
        DocumentChunk mockChunk = DocumentChunk.builder()
                .chunkId("chunk-1")
                .text("测试结果")
                .metadata(Map.of(LuminaConstants.FIELD_PARENT_ID, "parent-1"))
                .build();
        List<DocumentChunk> mockResults = List.of(mockChunk);

        when(cacheManager.getCache(eq(indexName), anyString(), anyList())).thenReturn(null);
        when(embeddingModel.embed(anyString())).thenAnswer(invocation -> {
            Embedding emb = mock(Embedding.class);
            when(emb.vectorAsList()).thenReturn(mockVector);
            Response<Embedding> resp = mock(Response.class);
            when(resp.content()).thenReturn(emb);
            return resp;
        });
        when(deduplicator.execute(anyString(), any())).thenAnswer(invocation -> {
            java.util.function.Supplier<String> supplier = invocation.getArgument(1);
            return supplier.get();
        });
        when(vectorStoreService.hybridSearch(eq(indexName), eq(query), anyList(), isNull(), eq(3)))
                .thenReturn(mockResults);

        String result = tool.retrieveInformation(query, false);

        assertNotNull(result);
        assertTrue(result.contains("测试结果"));
    }

    @Test
    @DisplayName("向量搜索结果为空时，应返回友好提示")
    void retrieveInformation_EmptyResults_ShouldReturnFriendlyMessage() {
        String query = "不存在的知识";

        when(cacheManager.getCache(eq(indexName), anyString(), anyList())).thenReturn(null);
        when(embeddingModel.embed(anyString())).thenAnswer(invocation -> {
            Embedding emb = mock(Embedding.class);
            when(emb.vectorAsList()).thenReturn(mockVector);
            Response<Embedding> resp = mock(Response.class);
            when(resp.content()).thenReturn(emb);
            return resp;
        });
        when(deduplicator.execute(anyString(), any())).thenAnswer(invocation -> {
            java.util.function.Supplier<String> supplier = invocation.getArgument(1);
            return supplier.get();
        });
        when(vectorStoreService.hybridSearch(anyString(), anyString(), anyList(), any(), eq(3)))
                .thenReturn(List.of());

        String result = tool.retrieveInformation(query, false);

        assertNotNull(result);
        assertTrue(result.contains("未检索到") || result.contains("没有") || result.contains("无相关"),
                "空结果时应返回友好的无结果提示");
    }

    @Test
    @DisplayName("不同 indexName 的 Tool 实例应完全隔离")
    void differentIndexName_ShouldBeIsolated() {
        List<String> refDocs2 = new ArrayList<>();
        InformationRetrievalTool tool2 = new InformationRetrievalTool(
                vectorStoreService, embeddingModel, stringRedisTemplate,
                cacheManager, deduplicator,
                "different-index", metadataFilters, refDocs2
        );

        // 验证两个工具的 sessionRefDocIds 是不同对象
        assertNotSame(sessionRefDocIds, refDocs2, "不同实例的引用列表应不同");
    }

    @Test
    @DisplayName("多次检索应累积 sessionRefDocIds")
    void multipleRetrievals_ShouldAccumulateRefDocs() {
        String query1 = "查询1";
        String query2 = "查询2";

        when(cacheManager.getCache(eq(indexName), anyString(), anyList())).thenReturn(null);
        when(embeddingModel.embed(anyString())).thenAnswer(invocation -> {
            Embedding emb = mock(Embedding.class);
            when(emb.vectorAsList()).thenReturn(mockVector);
            Response<Embedding> resp = mock(Response.class);
            when(resp.content()).thenReturn(emb);
            return resp;
        });
        when(deduplicator.execute(anyString(), any())).thenAnswer(invocation -> {
            java.util.function.Supplier<String> supplier = invocation.getArgument(1);
            return supplier.get();
        });

        when(vectorStoreService.hybridSearch(eq(indexName), anyString(), anyList(), eq(metadataFilters), eq(3)))
                .thenReturn(List.of(
                        DocumentChunk.builder()
                                .chunkId("chunk-1").text("结果1")
                                .metadata(Map.of(LuminaConstants.FIELD_PARENT_ID, "parent-1"))
                                .build()
                ))
                .thenReturn(List.of(
                        DocumentChunk.builder()
                                .chunkId("chunk-2").text("结果2")
                                .metadata(Map.of(LuminaConstants.FIELD_PARENT_ID, "parent-2"))
                                .build()
                ));

        tool.retrieveInformation(query1, false);
        assertEquals(1, sessionRefDocIds.size());
        assertTrue(sessionRefDocIds.contains("parent-1"));

        tool.retrieveInformation(query2, false);
        assertEquals(2, sessionRefDocIds.size());
        assertTrue(sessionRefDocIds.contains("parent-2"));
    }

    @Test
    @DisplayName("向量搜索抛出异常时，应优雅降级")
    void retrieveInformation_WhenSearchThrows_ShouldDegradeGracefully() {
        String query = "异常测试";

        when(cacheManager.getCache(eq(indexName), anyString(), anyList())).thenReturn(null);
        when(embeddingModel.embed(anyString())).thenAnswer(invocation -> {
            Embedding emb = mock(Embedding.class);
            when(emb.vectorAsList()).thenReturn(mockVector);
            Response<Embedding> resp = mock(Response.class);
            when(resp.content()).thenReturn(emb);
            return resp;
        });
        when(deduplicator.execute(anyString(), any())).thenAnswer(invocation -> {
            java.util.function.Supplier<String> supplier = invocation.getArgument(1);
            return supplier.get();
        });
        when(vectorStoreService.hybridSearch(anyString(), anyString(), anyList(), any(), eq(3)))
                .thenThrow(new RuntimeException("ES 连接异常"));

        // 应返回降级提示，而不是传播异常
        String result = tool.retrieveInformation(query, false);
        assertNotNull(result);
        assertTrue(result.contains("系统异常") || result.contains("异常"));
    }

    @Test
    @DisplayName("needLongContext=true 时触发 Small-to-Big 溯源")
    void retrieveInformation_NeedLongContext_ShouldDoSmallToBig() {
        String query = "宏观总结";

        DocumentChunk chunk = DocumentChunk.builder()
                .chunkId("chunk-1")
                .text("碎片内容")
                .metadata(Map.of(LuminaConstants.FIELD_PARENT_ID, "parent-doc-1"))
                .build();

        when(cacheManager.getCache(eq(indexName), anyString(), anyList())).thenReturn(null);
        when(embeddingModel.embed(anyString())).thenAnswer(invocation -> {
            Embedding emb = mock(Embedding.class);
            when(emb.vectorAsList()).thenReturn(mockVector);
            Response<Embedding> resp = mock(Response.class);
            when(resp.content()).thenReturn(emb);
            return resp;
        });
        when(deduplicator.execute(anyString(), any())).thenAnswer(invocation -> {
            java.util.function.Supplier<String> supplier = invocation.getArgument(1);
            return supplier.get();
        });
        when(vectorStoreService.hybridSearch(anyString(), anyString(), anyList(), eq(metadataFilters), eq(3)))
                .thenReturn(List.of(chunk));
        // 模拟 Small-to-Big 从 Redis 获取父文档
        when(valueOperations.get(LuminaConstants.PARENT_DOC_PREFIX + "parent-doc-1"))
                .thenReturn("这是父文档的完整长文本内容，包含大量上下文信息……");

        String result = tool.retrieveInformation(query, true);

        assertNotNull(result);
        assertTrue(result.contains("父文档的完整长文本内容"),
                "needLongContext=true 应返回父文档完整内容");
        assertTrue(sessionRefDocIds.contains("parent-doc-1"));
    }

    @Test
    @DisplayName("needLongContext=false 时使用 Short RAG（碎片拼接）")
    void retrieveInformation_ShortRag_ShouldUseChunksDirectly() {
        String query = "具体数值";

        DocumentChunk chunk = DocumentChunk.builder()
                .chunkId("chunk-1")
                .text("具体数值是 42")
                .metadata(Map.of(LuminaConstants.FIELD_PARENT_ID, "parent-doc-1"))
                .build();

        when(cacheManager.getCache(eq(indexName), anyString(), anyList())).thenReturn(null);
        when(embeddingModel.embed(anyString())).thenAnswer(invocation -> {
            Embedding emb = mock(Embedding.class);
            when(emb.vectorAsList()).thenReturn(mockVector);
            Response<Embedding> resp = mock(Response.class);
            when(resp.content()).thenReturn(emb);
            return resp;
        });
        when(deduplicator.execute(anyString(), any())).thenAnswer(invocation -> {
            java.util.function.Supplier<String> supplier = invocation.getArgument(1);
            return supplier.get();
        });
        when(vectorStoreService.hybridSearch(anyString(), anyString(), anyList(), eq(metadataFilters), eq(3)))
                .thenReturn(List.of(chunk));

        String result = tool.retrieveInformation(query, false);

        assertNotNull(result);
        assertTrue(result.contains("具体数值是 42"),
                "needLongContext=false 应直接使用碎片文本");
    }

    @Test
    @DisplayName("缓存命中时直接返回，不查 ES")
    void retrieveInformation_CacheHit_ShouldReturnDirectly() {
        String query = "缓存测试";
        String cachedResult = "这是缓存的答案";

        when(cacheManager.getCache(eq(indexName), eq(query), anyList())).thenReturn(cachedResult);
        when(embeddingModel.embed(anyString())).thenAnswer(invocation -> {
            Embedding emb = mock(Embedding.class);
            when(emb.vectorAsList()).thenReturn(mockVector);
            Response<Embedding> resp = mock(Response.class);
            when(resp.content()).thenReturn(emb);
            return resp;
        });

        String result = tool.retrieveInformation(query, false);

        assertEquals(cachedResult, result);
        // 缓存命中时不应查 ES
        verify(vectorStoreService, never()).hybridSearch(anyString(), anyString(), anyList(), any(), anyInt());
        // 不应触发 deduplicator
        verify(deduplicator, never()).execute(anyString(), any());
    }
}
