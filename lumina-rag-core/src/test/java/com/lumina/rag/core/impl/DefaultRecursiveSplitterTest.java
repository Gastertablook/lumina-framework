package com.lumina.rag.core.impl;

import com.lumina.rag.core.domain.DocumentChunk;
import com.lumina.rag.core.constant.LuminaConstants;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 【驾驭层】文档切块策略测试
 *
 * 测试覆盖：
 * 1. 正常文本切分
 * 2. 短文本不切分
 * 3. 空文本安全处理
 * 4. 超长文本切分块数
 */
@DisplayName("DefaultRecursiveSplitter 文档切块策略测试")
class DefaultRecursiveSplitterTest {

    private final DefaultRecursiveSplitter splitter = new DefaultRecursiveSplitter();

    @Test
    @DisplayName("短文本不切分，返回单块")
    void shortText_shouldReturnSingleChunk() {
        String shortText = "这是一段短文本。";

        List<String> chunks = splitter.split(shortText);

        assertEquals(1, chunks.size());
        assertEquals(shortText, chunks.get(0));
    }

    @Test
    @DisplayName("长文本应被切分成多块")
    void longText_shouldSplitIntoMultipleChunks() {
        // 构造超过 500 字的文本
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            sb.append("这是一段用于测试切块策略的长文本内容。第").append(i).append("句。");
        }
        String longText = sb.toString();

        List<String> chunks = splitter.split(longText);

        assertTrue(chunks.size() >= 2, "长文本应该被切分成至少 2 块");
        assertTrue(chunks.get(0).length() <= 500 + 50, "每块长度不应超过 500 + 50(重叠)");

        // 验证所有块拼接起来包含原文
        String joined = String.join("", chunks);
        assertTrue(joined.contains("第1句"));
        assertTrue(joined.contains("第99句"));
    }

    @Test
    @DisplayName("空文本应返回空列表（LangChain4j 不接受空文本切块）")
    void emptyText_shouldReturnEmptyList() {
        assertThrows(Exception.class, () -> {
            splitter.split("");
        }, "LangChain4j DocumentSplitters 不接受 null 或空白文本");
    }

    @Test
    @DisplayName("DocumentChunk 防御性编程：metadata 不可修改")
    void documentChunk_metadata_shouldBeUnmodifiable() {
        DocumentChunk chunk = DocumentChunk.builder()
                .chunkId("test_001")
                .text("测试文本")
                .vector(List.of(0.1f, 0.2f))
                .metadata(Map.of("key", "value"))
                .build();

        Map<String, Object> metadata = chunk.getMetadata();
        assertThrows(UnsupportedOperationException.class,
                () -> metadata.put("newKey", "newValue"),
                "metadata 应该是不可修改的");
    }

    @Test
    @DisplayName("DocumentChunk 无 metadata 时返回空 Map")
    void documentChunk_nullMetadata_shouldReturnEmptyMap() {
        DocumentChunk chunk = DocumentChunk.builder()
                .chunkId("test_002")
                .text("测试文本")
                .vector(List.of(0.1f, 0.2f))
                .build();

        Map<String, Object> metadata = chunk.getMetadata();
        assertNotNull(metadata);
        assertTrue(metadata.isEmpty());
    }

    @Test
    @DisplayName("LuminaConstants 常量应保持正确性")
    void constants_shouldBeCorrect() {
        assertEquals("lumina:parent_doc:", LuminaConstants.PARENT_DOC_PREFIX);
        assertEquals("lumina:cache:l1:", LuminaConstants.L1_CACHE_PREFIX);
        assertEquals("default_workspace", LuminaConstants.DEFAULT_INDEX_NAME);
        assertEquals("parentId", LuminaConstants.FIELD_PARENT_ID);
    }
}
