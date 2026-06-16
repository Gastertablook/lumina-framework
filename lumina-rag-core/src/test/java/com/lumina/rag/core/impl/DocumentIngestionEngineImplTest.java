package com.lumina.rag.core.impl;

import com.lumina.rag.core.domain.DocumentChunk;
import com.lumina.rag.core.spi.DocumentSplitterStrategy;
import com.lumina.rag.core.spi.VectorStoreService;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 【驾驭层】文档摄入引擎全方位测试
 *
 * 测试覆盖：
 * 1. 文档摄入全流程：存Redis父文档 → 切块 → 向量化 → 打烙印 → 存ES
 * 2. 删除父文档
 * 3. 完整 removeDocument (删ES碎片 + 删Redis父文档)
 * 4. 自定义切块策略委派
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DocumentIngestionEngine 文档摄入引擎测试")
class DocumentIngestionEngineImplTest {

    @Mock
    private VectorStoreService vectorStoreService;
    @Mock
    private EmbeddingModel embeddingModel;
    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;
    @Mock
    private DocumentSplitterStrategy splitterStrategy;

    @Captor
    private ArgumentCaptor<List<DocumentChunk>> chunksCaptor;

    private DocumentIngestionEngineImpl ingestionEngine;

    @BeforeEach
    void setUp() {
        lenient().when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        ingestionEngine = new DocumentIngestionEngineImpl(
                vectorStoreService, embeddingModel, stringRedisTemplate, splitterStrategy
        );
    }

    // ==================== 1. 文档摄入全流程 ====================

    @Test
    @DisplayName("文档摄入全流程：存Redis → 切块 → 向量化 → 打烙印 → 存ES")
    void ingest_fullFlow() {
        String sourceName = "JVM规范.pdf";
        String text = "Java内存模型是Java虚拟机规范的一部分..." +
                "它定义了多线程程序中共享变量的访问规则..." +
                "happens-before规则是JMM的核心概念...";
        String indexName = "test_kb";

        // 切块
        List<String> chunks = List.of(
                "Java内存模型是Java虚拟机规范的一部分",
                "它定义了多线程程序中共享变量的访问规则",
                "happens-before规则是JMM的核心概念"
        );
        when(splitterStrategy.split(text)).thenReturn(chunks);

        // Embedding
        Embedding mockEmbedding = new Embedding(new float[]{0.1f, 0.2f, 0.3f});
        lenient().when(embeddingModel.embed(anyString())).thenReturn(Response.from(mockEmbedding));

        // 执行
        String parentId = ingestionEngine.ingest(sourceName, text, indexName);

        // 验证 1：返回的 parentId 格式正确
        assertNotNull(parentId);
        assertTrue(parentId.startsWith("doc_"), "parentId 必须以 doc_ 开头");

        // 验证 2：Redis 父文档存储（30天TTL）
        verify(valueOperations).set(
                eq("lumina:parent_doc:" + parentId),
                eq(text),
                eq(30L),
                eq(TimeUnit.DAYS)
        );

        // 验证 3：切块策略被调用
        verify(splitterStrategy).split(text);

        // 验证 4：向量化被调用（每块一次）
        verify(embeddingModel, times(chunks.size())).embed(anyString());

        // 验证 5：ES 存储
        verify(vectorStoreService).saveChunks(eq(indexName), chunksCaptor.capture());
        List<DocumentChunk> savedChunks = chunksCaptor.getValue();
        assertEquals(chunks.size(), savedChunks.size());

        // 验证 6：每块都有 parentId 烙印
        for (DocumentChunk chunk : savedChunks) {
            assertEquals(parentId, chunk.getMetadata().get("parentId"),
                    "每个碎片都必须有 parentId 烙印");
            assertEquals(sourceName, chunk.getMetadata().get("sourceName"),
                    "每个碎片都必须有 sourceName 元数据");
        }
    }

    // ==================== 2. 自定义切块策略 ====================

    @Test
    @DisplayName("摄入引擎应委派给注入的切块策略")
    void ingest_shouldUseInjectedSplitter() {
        String text = "一段很长的文档内容...";
        List<String> customChunks = List.of("自定义块1", "自定义块2", "自定义块3");
        when(splitterStrategy.split(text)).thenReturn(customChunks);

        Embedding mockEmbedding = new Embedding(new float[]{0.1f, 0.2f, 0.3f});
        lenient().when(embeddingModel.embed(anyString())).thenReturn(Response.from(mockEmbedding));

        ingestionEngine.ingest("test.txt", text, "test_index");

        // 验证使用的是自定义切块结果
        verify(vectorStoreService).saveChunks(eq("test_index"), chunksCaptor.capture());
        List<DocumentChunk> saved = chunksCaptor.getValue();
        assertEquals(3, saved.size());
        assertEquals("自定义块1", saved.get(0).getText());
    }

    // ==================== 3. 删除父文档 ====================

    @Test
    @DisplayName("deleteParentDoc 应从 Redis 删除父文档")
    void deleteParentDoc_shouldRemoveFromRedis() {
        String parentId = "doc_abc123";

        ingestionEngine.deleteParentDoc(parentId);

        verify(stringRedisTemplate).delete("lumina:parent_doc:" + parentId);
    }

    // ==================== 4. 完整 removeDocument ====================

    @Test
    @DisplayName("removeDocument 应删除 ES 碎片和 Redis 父文档")
    void removeDocument_shouldDeleteBothESAndRedis() {
        String indexName = "test_index";
        String parentId = "doc_to_delete";

        ingestionEngine.removeDocument(indexName, parentId);

        // 验证 ES 碎片被删除
        verify(vectorStoreService).deleteChunksByParentId(indexName, parentId);
        // 验证 Redis 父文档被删除
        verify(stringRedisTemplate).delete("lumina:parent_doc:" + parentId);
    }

    // ==================== 5. UUID 唯一性 ====================

    @Test
    @DisplayName("连续两次 ingest 应该生成不同的 parentId")
    void consecutiveIngest_shouldGenerateDifferentParentIds() {
        when(splitterStrategy.split(anyString())).thenReturn(List.of("chunk"));
        Embedding mockEmbedding = new Embedding(new float[]{0.1f, 0.2f, 0.3f});
        lenient().when(embeddingModel.embed(anyString())).thenReturn(Response.from(mockEmbedding));

        String parentId1 = ingestionEngine.ingest("doc1", "内容1", "index");
        String parentId2 = ingestionEngine.ingest("doc2", "内容2", "index");

        assertNotEquals(parentId1, parentId2, "每次摄入必须生成全局唯一的 parentId");
    }
}
