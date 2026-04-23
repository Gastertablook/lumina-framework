package com.lumina.docs.service;

import com.lumina.rag.core.spi.DocumentIngestionEngine;
import com.lumina.rag.core.spi.VectorStoreService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import static com.lumina.rag.core.constant.LuminaConstants.PARENT_DOC_PREFIX;
import static com.lumina.rag.core.constant.LuminaConstants.TOPIC_DOC_UPDATE;

/**
 * 业务层知识库服务
 * 现在它薄得像一张纸，所有的脏活累活全被底层轮子 (DocumentIngestionEngine) 包揽了！
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeBaseService {

    private final DocumentIngestionEngine documentIngestionEngine;
    private final VectorStoreService vectorStoreService;
    private final StringRedisTemplate stringRedisTemplate;
    private final KafkaTemplate<String, String> kafkaTemplate;

    /**
     * 业务操作：纯粹的上传入库
     */
    public String ingestText(String sourceName, String text, String indexName) {
        log.info("业务层收到文档 [{}], 准备委托给核心轮子进行黑盒摄入...", sourceName);

        // 核心轮子接管：自动存Redis、自动切块策略、自动打烙印、自动存ES！
        // 并且返回绝对安全的 parentId
        String parentId = documentIngestionEngine.ingest(sourceName, text, indexName);

        log.info("业务层摄入完成！拿到核心引擎返回的 ParentID: {}", parentId);
        return parentId;
    }

    /**
     * 业务操作：高危的数据更新与缓存炸毁编排
     */
    public String updateDocument(String oldDocId, String newText, String indexName) {
        log.info("业务层开始执行文档更新与销毁流程，目标旧ID: {}", oldDocId);

        // 1. ES 底层碎片清理
        vectorStoreService.deleteChunksByParentId(indexName, oldDocId);

        // 2. Redis 父文档清理 (使用我们刚定义的常量)
        stringRedisTemplate.delete(PARENT_DOC_PREFIX + oldDocId);

        // 3. 触发 Kafka 广播清理 AI 缓存
        log.info("业务层发送 Kafka 消息，通知全网清理 docId: {} 的 AI 缓存", oldDocId);
        kafkaTemplate.send(TOPIC_DOC_UPDATE, oldDocId);

        // 4. 将新文本重新喂给摄入引擎，生成全新的 parentId 和 切块
        String newParentId = documentIngestionEngine.ingest("更新后的档案", newText, indexName);

        log.info("业务层更新流程完毕！旧文档已销毁，新文档 ParentID: {}", newParentId);
        return newParentId;
    }
}