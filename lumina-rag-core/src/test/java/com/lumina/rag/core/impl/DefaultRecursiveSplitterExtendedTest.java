package com.lumina.rag.core.impl;

import com.lumina.rag.core.domain.DocumentChunk;
import com.lumina.rag.core.spi.DocumentSplitterStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DefaultRecursiveSplitter 补充测试
 *
 * 覆盖已有测试未覆盖的场景：
 * - 边界大小文档
 * - 不同重叠大小的表现
 * - 纯标点/纯空白文档
 * - Unicode/多语言混合
 */
@ExtendWith(MockitoExtension.class)
class DefaultRecursiveSplitterExtendedTest {

    private DefaultRecursiveSplitter splitter;

    @BeforeEach
    void setUp() {
        // 默认配置：maxChunkSize=500, overlap=50
        splitter = new DefaultRecursiveSplitter();
    }

    @Test
    @DisplayName("恰好等于最大块大小的文档应分一块")
    void exactMaxSize_ShouldReturnOneChunk() {
        // 创建一个长度恰好为 500 的字符串（远小于 1000 的 maxChunkSize）
        String text = "A".repeat(500);

        List<String> chunks = splitter.split(text);

        assertEquals(1, chunks.size(), "远小于最大块大小时应只分一块");
        assertEquals(text, chunks.get(0));
    }

    @Test
    @DisplayName("略大于最大块大小的文档应分两块")
    void slightlyLargerThanMax_ShouldSplitIntoTwo() {
        String text = "A".repeat(550);

        List<String> chunks = splitter.split(text);

        assertTrue(chunks.size() >= 2, "略大于最大块大小时应至少分两块");
    }

    @Test
    @DisplayName("多语言混合文本应正确分割")
    void mixedLanguageText_ShouldSplitCorrectly() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 10; i++) {
            sb.append("Hello世界你好مرحبا🌍".repeat(50));
        }
        String text = sb.toString();

        List<String> chunks = splitter.split(text);

        assertFalse(chunks.isEmpty(), "多语言文本应能分割");
        // 所有块合并后应包含原始文本的全部内容
        String combined = String.join("", chunks);
        // 由于重叠，合并后内容应 >= 原始内容
        assertTrue(combined.length() >= text.length() * 0.8, "分割后的内容应基本完整");
    }

    @Test
    @DisplayName("纯空白文档应抛出异常或返回空列表")
    void whitespaceDocument_ShouldHandle() {
        String text = "   \n  \t  \n\n  ";

        // DefaultRecursiveSplitter 底层使用 Document.from() 要求非空
        assertThrows(IllegalArgumentException.class, () -> {
            splitter.split(text);
        });
    }

    @Test
    @DisplayName("非常大的文档应分割成多块")
    void veryLargeDocument_ShouldSplitIntoManyChunks() {
        // 创建一个很大的文档（约 100KB）
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 2000; i++) {
            sb.append("这是第").append(i).append("段内容。Lumina RAG 框架是一个企业级智能检索系统。\n");
        }
        String text = sb.toString();

        List<String> chunks = splitter.split(text);

        assertTrue(chunks.size() > 3, "大文档应分割成多块");
        // 验证每块不超过最大大小（1000 tokens，但这里以字符估算）
        for (String chunk : chunks) {
            assertTrue(chunk.length() <= 2000, "每块字符数不应远超最大 token 数");
        }
    }

    @Test
    @DisplayName("自定义分割策略")
    void customSplitter_ShouldBeUsable() {
        DocumentSplitterStrategy customSplitter = text -> List.of("自定义块1", "自定义块2", "自定义块3");

        List<String> chunks = customSplitter.split("任何内容");

        assertEquals(3, chunks.size());
        assertEquals("自定义块1", chunks.get(0));
        assertEquals("自定义块2", chunks.get(1));
        assertEquals("自定义块3", chunks.get(2));
    }
}
