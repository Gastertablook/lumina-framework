package com.lumina.rag.core.agent;

import com.lumina.rag.core.cache.SemanticCacheManager;
import com.lumina.rag.core.concurrent.RequestDeduplicator;
import com.lumina.rag.core.constant.LuminaConstants;
import com.lumina.rag.core.domain.DocumentChunk;
import com.lumina.rag.core.spi.VectorStoreService;
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
 * 【驾驭层】Agent 检索工具全方位测试
 *
 * 测试覆盖：
 * 1. 缓存命中 → 直接返回，不查 ES
 * 2. 缓存未命中 → Singleflight → Double-Check → ES 混合检索 → Small-to-Big
 * 3. Small-to-Big 长文溯源 (needLongContext=true)
 * 4. Short RAG 高精度碎片 (needLongContext=false)
 * 5. ES 无数据 → 返回系统警告
 * 6. 引用传递 refDocIds 血缘写入
 * 7. 不同 indexName 和 filters 线程隔离
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("InformationRetrievalTool Agent 检索工具测试")
class InformationRetrievalToolTest {

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
    private List<String> refDocIds;
    private final String indexName = "test_workspace";
    private final String keyword = "Java 内存模型";
    private final Map<String, Object> filters = Map.of("tenantId", "1001");

    @BeforeEach
    void setUp() {
        lenient().when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        refDocIds = new ArrayList<>();

        // 模拟 embedding: embed() 返回 Response<Embedding>
        Embedding mockEmbedding = new Embedding(new float[]{0.1f, 0.2f, 0.3f, 0.4f});
        lenient().when(embeddingModel.embed(anyString())).thenReturn(Response.from(mockEmbedding));

        tool = new InformationRetrievalTool(
                vectorStoreService, embeddingModel, stringRedisTemplate,
                cacheManager, deduplicator,
                indexName, filters, refDocIds
        );
    }

    // ==================== 1. 缓存命中测试 ====================

    @Test
    @DisplayName("语义缓存命中时直接返回结果，不执行 ES 检索")
    void cacheHit_shouldReturnDirectly() {
        String cachedResult = "Java内存模型是JVM规范中定义的多线程内存访问规则...";
        when(cacheManager.getCache(eq(indexName), eq(keyword), anyList()))
                .thenReturn(cachedResult);

        String result = tool.retrieveInformation(keyword, false);

        assertEquals(cachedResult, result);
        // 验证没有触发 Singleflight 和 ES 检索
        verify(deduplicator, never()).execute(anyString(), any());
        verify(vectorStoreService, never()).hybridSearch(anyString(), anyString(), anyList(), anyMap(), anyInt());
    }

    // ==================== 2. 缓存未命中 → Singleflight → ES 检索 ====================

    @Test
    @DisplayName("缓存未命中时走 Singleflight，执行混合检索")
    void cacheMiss_shouldTriggerSingleflightAndSearch() {
        // 缓存未命中
        when(cacheManager.getCache(eq(indexName), eq(keyword), anyList()))
                .thenReturn(null);

        // ES 检索返回结果
        DocumentChunk chunk = DocumentChunk.builder()
                .chunkId("chunk_001")
                .text("Java内存模型(JMM)是一组规范")
                .vector(List.of(0.1f, 0.2f, 0.3f))
                .metadata(Map.of(LuminaConstants.FIELD_PARENT_ID, "doc_parent_123"))
                .build();
        when(vectorStoreService.hybridSearch(anyString(), anyString(), anyList(), anyMap(), anyInt()))
                .thenReturn(List.of(chunk));

        // 模拟 Singleflight 执行真实操作
        when(deduplicator.execute(anyString(), any()))
                .thenAnswer(invocation -> {
                    // 调用 Singleflight 内的真实逻辑
                    // 注意：这里我们需要模拟 Double-Check 未命中
                    when(cacheManager.getCache(eq(indexName), eq(keyword), anyList()))
                            .thenReturn(null, null); // 第一次调用返回 null (getCache)，第二次也返回 null (doubleCheck)
                    // 直接调用真实操作
                    return invocation.<java.util.function.Supplier<String>>getArgument(1).get();
                });

        String result = tool.retrieveInformation(keyword, false);

        assertNotNull(result);
        assertTrue(result.contains("Java内存模型(JMM)是一组规范"));
        // 验证 refDocIds 被写入
        assertTrue(refDocIds.contains("doc_parent_123"));
        // 验证缓存被写入
        verify(cacheManager).putCache(eq(indexName), eq(keyword), anyList(), anyString(), eq(refDocIds));
    }

    // ==================== 3. Small-to-Big 长文溯源 ====================

    @Test
    @DisplayName("needLongContext=true 时应该从 Redis 拉取完整父文档")
    void needLongContext_shouldFetchParentDocs() {
        when(cacheManager.getCache(eq(indexName), eq(keyword), anyList()))
                .thenReturn(null);

        DocumentChunk chunk = DocumentChunk.builder()
                .chunkId("chunk_001")
                .text("JMM碎片")
                .vector(List.of(0.1f, 0.2f, 0.3f))
                .metadata(Map.of(LuminaConstants.FIELD_PARENT_ID, "doc_parent_456"))
                .build();
        when(vectorStoreService.hybridSearch(anyString(), anyString(), anyList(), anyMap(), anyInt()))
                .thenReturn(List.of(chunk));

        // Redis 中存储的完整父文档
        String parentDocText = "Java内存模型完整长文...包含大量上下文信息...";
        when(valueOperations.get(LuminaConstants.PARENT_DOC_PREFIX + "doc_parent_456"))
                .thenReturn(parentDocText);

        when(deduplicator.execute(anyString(), any()))
                .thenAnswer(invocation -> {
                    when(cacheManager.getCache(eq(indexName), eq(keyword), anyList()))
                            .thenReturn(null, null);
                    return invocation.<java.util.function.Supplier<String>>getArgument(1).get();
                });

        String result = tool.retrieveInformation(keyword, true);

        // 验证返回的是完整父文档，不是碎片
        assertTrue(result.contains("Java内存模型完整长文"));
        assertFalse(result.contains("JMM碎片"));
    }

    // ==================== 4. Short RAG 高精度碎片 ====================

    @Test
    @DisplayName("needLongContext=false 时仅使用高精度碎片")
    void shortRag_shouldUseOnlyChunks() {
        when(cacheManager.getCache(eq(indexName), eq(keyword), anyList()))
                .thenReturn(null);

        DocumentChunk chunk1 = DocumentChunk.builder()
                .chunkId("chunk_001")
                .text("碎片1：JMM定义了happens-before规则")
                .vector(List.of(0.1f, 0.2f))
                .metadata(Map.of(LuminaConstants.FIELD_PARENT_ID, "doc_parent_789"))
                .build();
        DocumentChunk chunk2 = DocumentChunk.builder()
                .chunkId("chunk_002")
                .text("碎片2：volatile的内存语义")
                .vector(List.of(0.3f, 0.4f))
                .metadata(Map.of(LuminaConstants.FIELD_PARENT_ID, "doc_parent_789"))
                .build();
        when(vectorStoreService.hybridSearch(anyString(), anyString(), anyList(), anyMap(), anyInt()))
                .thenReturn(List.of(chunk1, chunk2));

        when(deduplicator.execute(anyString(), any()))
                .thenAnswer(invocation -> {
                    when(cacheManager.getCache(eq(indexName), eq(keyword), anyList()))
                            .thenReturn(null, null);
                    return invocation.<java.util.function.Supplier<String>>getArgument(1).get();
                });

        String result = tool.retrieveInformation(keyword, false);

        // 验证返回的是碎片拼接（非父文档）
        assertTrue(result.contains("碎片1"));
        assertTrue(result.contains("碎片2"));
        assertTrue(result.contains("---")); // 碎片用 --- 分隔
    }

    // ==================== 5. ES 无数据 ====================

    @Test
    @DisplayName("ES 检索无结果时应返回系统警告，要求 Agent 停止作答")
    void noData_shouldReturnSystemWarning() {
        when(cacheManager.getCache(eq(indexName), eq(keyword), anyList()))
                .thenReturn(null);

        when(vectorStoreService.hybridSearch(anyString(), anyString(), anyList(), anyMap(), anyInt()))
                .thenReturn(List.of()); // 空结果

        when(deduplicator.execute(anyString(), any()))
                .thenAnswer(invocation -> {
                    when(cacheManager.getCache(eq(indexName), eq(keyword), anyList()))
                            .thenReturn(null, null);
                    return invocation.<java.util.function.Supplier<String>>getArgument(1).get();
                });

        String result = tool.retrieveInformation(keyword, false);

        assertNotNull(result);
        // 空结果时应返回系统提示信息（不包含具体知识内容）
        assertTrue(result.contains("警告") || result.contains("抱歉") || result.contains("没有"),
                "空结果时应返回系统提示信息，实际: " + result);
    }

    // ==================== 6. 不同工具实例线程隔离 ====================

    @Test
    @DisplayName("不同 indexName/filters 的工具实例互不干扰")
    void differentInstances_shouldIsolate() {
        List<String> refDocIdsA = new ArrayList<>();
        List<String> refDocIdsB = new ArrayList<>();

        InformationRetrievalTool toolA = new InformationRetrievalTool(
                vectorStoreService, embeddingModel, stringRedisTemplate,
                cacheManager, deduplicator,
                "tenant_a", Map.of("tenant", "A"), refDocIdsA
        );
        InformationRetrievalTool toolB = new InformationRetrievalTool(
                vectorStoreService, embeddingModel, stringRedisTemplate,
                cacheManager, deduplicator,
                "tenant_b", Map.of("tenant", "B"), refDocIdsB
        );

        // 两个工具实例使用不同的缓存键
        verify(cacheManager, never()).getCache(eq("tenant_a"), anyString(), anyList());

        // 验证工具 A 使用租户 A 的配置
        when(cacheManager.getCache(eq("tenant_a"), eq("keyword"), anyList()))
                .thenReturn("租户A的回答");
        String resultA = toolA.retrieveInformation("keyword", false);
        assertEquals("租户A的回答", resultA);

        // 验证工具 B 使用租户 B 的配置
        when(cacheManager.getCache(eq("tenant_b"), eq("keyword"), anyList()))
                .thenReturn("租户B的回答");
        String resultB = toolB.retrieveInformation("keyword", false);
        assertEquals("租户B的回答", resultB);

        // 验证两个工具的 refDocIds 是不同对象
        assertNotSame(refDocIdsA, refDocIdsB);
    }

    // ==================== 7. Double-Check 命中测试 ====================

    @Test
    @DisplayName("Double-Check 检测到缓存时复用，不查 ES")
    void doubleCheckHit_shouldReuseCache() {
        // 第一次 getCache 返回 null（初始缓存未命中）
        // 第二次 getCache（doubleCheck）返回缓存（跟随者复用）
        String cachedResponse = "Double-Check 命中的缓存结果";

        when(cacheManager.getCache(eq(indexName), eq(keyword), anyList()))
                .thenReturn(null); // 初始未命中

        when(deduplicator.execute(anyString(), any()))
                .thenAnswer(invocation -> {
                    // Double-Check 命中！
                    when(cacheManager.getCache(eq(indexName), eq(keyword), anyList()))
                            .thenReturn(cachedResponse);
                    return invocation.<java.util.function.Supplier<String>>getArgument(1).get();
                });

        String result = tool.retrieveInformation(keyword, false);

        assertEquals(cachedResponse, result);
        // Double-Check 命中，不应该查 ES
        verify(vectorStoreService, never()).hybridSearch(anyString(), anyString(), anyList(), anyMap(), anyInt());
    }
}
