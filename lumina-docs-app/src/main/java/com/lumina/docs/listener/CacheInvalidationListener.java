package com.lumina.docs.listener;

import com.lumina.rag.core.cache.SemanticCacheManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class CacheInvalidationListener {

    private static final String TOPIC_DOC_UPDATE = "doc_update_topic";
    private static final String KAFKA_GROUP_ID = "lumina-docs-group";

    // 引入我们核心轮子的缓存终结者！
    private final SemanticCacheManager semanticCacheManager;

    @KafkaListener(topics = TOPIC_DOC_UPDATE, groupId = KAFKA_GROUP_ID)
    public void onDocUpdate(String docId) {
        log.info("接收到 Kafka 消息，文档 [{}] 发生变更！准备呼叫底层 V8 引擎执行 GC...", docId);
        // 扣动扳机！调用核心轮子物理炸毁相关缓存！
        semanticCacheManager.invalidateCacheByDocId(docId);
        log.info("底层缓存清理完毕，数据一致性已恢复！");
    }
}