package com.lumina.rag.core.spi;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.util.Map;

/**
 * 【驾驭层级：对外暴露的唯一合法网关 (Facade)】
 * 隔离了底层所有的 Cache、Singleflight、ES 检索逻辑。
 */
public interface LuminaRagClient {

    /**
     * 流式知识库对话核心入口
     *
     * @param query 用户提问
     * @param sessionId 会话ID (用于长文本多轮对话隔离)
     * @param indexName 指定搜对应库名
     * @param metadataFilters 驾驭约束参数 (如: {"userId": "1001", "docIds":["pdf_1", "pdf_2"]})
     *                    通过这些约束，底层检索时绝对不会发生数据越权。
     * @return SSE 打字机推流
     */
    SseEmitter chatStream(
            String query,
            String sessionId,
            String indexName,
            Map<String, Object> metadataFilters
    );
}