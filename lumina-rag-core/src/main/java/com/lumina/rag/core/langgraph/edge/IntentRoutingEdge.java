package com.lumina.rag.core.langgraph.edge;

import com.lumina.rag.core.langgraph.state.RagState;
import lombok.extern.slf4j.Slf4j;

/**
 * 【RAG 工作流】意图路由条件边
 *
 * 根据 IntentClassifierNode 的输出，决定下一步走向：
 * - RETRIEVE → 走检索链路（KeywordExtract → Retrieve → Generate）
 * - GENERAL  → 走常规回复（GeneralResponse）
 * - UNKNOWN  → 走错误处理
 */
@Slf4j
public class IntentRoutingEdge implements ConditionalEdge<RagState> {

    /** 节点名称常量 */
    public static final String KEYWORD_EXTRACT = "keyword_extract";
    public static final String GENERAL_RESPONSE = "general_response";
    public static final String ERROR_HANDLER = "error_handler";

    @Override
    public String evaluate(RagState state) {
        RagState.Intent intent = state.getIntent();
        if (intent == null) {
            intent = RagState.Intent.UNKNOWN;
        }

        String nextNode;
        switch (intent) {
            case RETRIEVE:
                nextNode = KEYWORD_EXTRACT;
                break;
            case GENERAL:
                nextNode = GENERAL_RESPONSE;
                break;
            case UNKNOWN:
            default:
                nextNode = ERROR_HANDLER;
                break;
        }

        log.info("[意图路由] 意图={} → 下一节点={}", intent, nextNode);
        return nextNode;
    }
}
