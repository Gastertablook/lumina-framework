package com.lumina.rag.core.domain;

import lombok.Builder;
import lombok.Getter;

import java.util.Map;
import java.util.List;
import java.util.Collections;

/**
 * 【驾驭层级：严格约束】
 * 引擎内部流转的统一标准结构
 * 使用 @Getter 和 final 保证一旦生成，大模型或任何中间件无法篡改其上下文状态。
 */
@Getter
@Builder
public class DocumentChunk {
    private final String chunkId;      // 切块ID (如 UUID)
    private final String text;         // 文本内容 (传给LLM和ES进行全文检索的文本)
    private final List<Float> vector;  // 文本对应的向量

    // 面向未来的终极扩展点：Metadata
    // 在电商里，这可以存 { "brandId": 6, "price": 1999 }
    // 在文档系统里，这可以存 { "fileName": "JVM规范.pdf", "page": 15, "parentId": "doc_123" }
    // parentId 是未来实现 Small-to-Big 检索的核心线索！
    private final Map<String, Object> metadata;

    // 防御性编程：防止外部修改 metadata 导致上下文污染
    public Map<String, Object> getMetadata() {
        return metadata == null ? Collections.<String, Object>emptyMap() : Collections.unmodifiableMap(metadata);
    }
}