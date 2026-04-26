package com.lumina.rag.core.constant;

/**
 * 【驾驭层】全局魔法字符串统一定义中心
 */
public interface LuminaConstants {

    // ================== Redis 缓存键前缀 ==================
    String PARENT_DOC_PREFIX = "lumina:parent_doc:";
    String L1_CACHE_PREFIX = "lumina:cache:l1:";

    // ================== 系统路由默认值 ==================
    String DEFAULT_INDEX_NAME = "default_workspace";

    // ================== ES 实体字段名 (防拼写错误) ==================
    String FIELD_PARENT_ID = "parentId";
    String FIELD_INDEX_NAME = "indexName";
    String FIELD_QUERY_TEXT = "queryText";
}