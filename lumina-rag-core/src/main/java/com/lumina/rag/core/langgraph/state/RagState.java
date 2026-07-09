package com.lumina.rag.core.langgraph.state;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 【RAG 工作流】RAG 流程专用状态
 *
 * 记录了整个 RAG 工作流中每一步的数据：
 * 1. 用户输入 → 2. 意图分类 → 3. 关键词提取 → 4. 知识库检索 → 5. 答案生成
 *
 * 字段说明：
 * - userMessage:     用户原始输入
 * - intent:          分类结果（RETRIEVE / GENERAL / UNKNOWN）
 * - keyword:         从输入中提取的搜索关键词
 * - needLongContext: 是否需要长篇上下文
 * - retrievedDocs:   检索到的文档片段列表
 * - finalAnswer:     最终生成的回答
 * - error:           流程中的错误信息
 * - history:         对话历史（sessionId 列表）
 */
public class RagState extends AgentState {

    // ============ 状态键常量 ============
    public static final String KEY_USER_MESSAGE   = "userMessage";
    public static final String KEY_INTENT         = "intent";
    public static final String KEY_KEYWORD        = "keyword";
    public static final String KEY_NEED_LONG_CTX  = "needLongContext";
    public static final String KEY_RETRIEVED_DOCS = "retrievedDocs";
    public static final String KEY_FINAL_ANSWER   = "finalAnswer";
    public static final String KEY_ERROR          = "error";
    public static final String KEY_SESSION_ID     = "sessionId";
    public static final String KEY_REF_DOC_IDS    = "refDocIds";
    public static final String KEY_HISTORY        = "history";
    public static final String KEY_INDEX_NAME     = "indexName";

    public RagState() {
        super();
    }

    public RagState(Map<String, Object> initialData) {
        super(initialData);
    }

    // ============ 便捷访问器 ============

    public String getUserMessage() {
        return get(KEY_USER_MESSAGE);
    }

    public void setUserMessage(String userMessage) {
        set(KEY_USER_MESSAGE, userMessage);
    }

    public Intent getIntent() {
        return get(KEY_INTENT);
    }

    public void setIntent(Intent intent) {
        set(KEY_INTENT, intent);
    }

    public String getKeyword() {
        return get(KEY_KEYWORD);
    }

    public void setKeyword(String keyword) {
        set(KEY_KEYWORD, keyword);
    }

    public boolean isNeedLongContext() {
        Boolean val = get(KEY_NEED_LONG_CTX);
        return val != null && val;
    }

    public void setNeedLongContext(boolean needLongContext) {
        set(KEY_NEED_LONG_CTX, needLongContext);
    }

    @SuppressWarnings("unchecked")
    public List<String> getRetrievedDocs() {
        List<String> docs = get(KEY_RETRIEVED_DOCS);
        return docs != null ? docs : List.of();
    }

    public void setRetrievedDocs(List<String> retrievedDocs) {
        set(KEY_RETRIEVED_DOCS, retrievedDocs);
    }

    public String getFinalAnswer() {
        return get(KEY_FINAL_ANSWER);
    }

    public void setFinalAnswer(String finalAnswer) {
        set(KEY_FINAL_ANSWER, finalAnswer);
    }

    public String getError() {
        return get(KEY_ERROR);
    }

    public void setError(String error) {
        set(KEY_ERROR, error);
    }

    public String getSessionId() {
        return get(KEY_SESSION_ID);
    }

    public void setSessionId(String sessionId) {
        set(KEY_SESSION_ID, sessionId);
    }

    @SuppressWarnings("unchecked")
    public List<String> getRefDocIds() {
        List<String> ids = get(KEY_REF_DOC_IDS);
        return ids != null ? ids : List.of();
    }

    public void setRefDocIds(List<String> refDocIds) {
        set(KEY_REF_DOC_IDS, refDocIds);
    }

    public String getIndexName() {
        return get(KEY_INDEX_NAME);
    }

    public void setIndexName(String indexName) {
        set(KEY_INDEX_NAME, indexName);
    }

    @SuppressWarnings("unchecked")
    public List<String> getHistory() {
        List<String> history = get(KEY_HISTORY);
        return history != null ? history : List.of();
    }

    public void setHistory(List<String> history) {
        set(KEY_HISTORY, history);
    }

    /**
     * 用户意图枚举
     */
    public enum Intent {
        /** 需要检索知识库（如：查询业务数据、事实性问题） */
        RETRIEVE,
        /** 常规对话（如：打招呼、翻译、算术） */
        GENERAL,
        /** 无法分类 */
        UNKNOWN
    }
}
