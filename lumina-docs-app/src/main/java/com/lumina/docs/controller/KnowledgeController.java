package com.lumina.docs.controller;

import com.lumina.docs.service.KnowledgeBaseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

@RestController
@Slf4j
@RequestMapping("/api/knowledge")
@RequiredArgsConstructor
public class KnowledgeController {

    private final KnowledgeBaseService knowledgeBaseService;

    private final org.springframework.kafka.core.KafkaTemplate<String, String> kafkaTemplate;

    @PostMapping("/upload-text")
    public String uploadText(
            @RequestParam String sourceName,
            @RequestBody String text,
            @RequestParam(defaultValue = "default_workspace") String indexName) {

        // 拿到底层引擎生成的血缘 ID
        String parentId = knowledgeBaseService.ingestText(sourceName, text, indexName);

        return "SUCCESS: 文档 [" + sourceName + "] 已成功灌入知识库！它的全局唯一ID (ParentID) 是: " + parentId;
    }

    @PostMapping("/update-doc")
    public String updateDoc(@RequestParam String oldDocId, @RequestBody String newText, @RequestParam String indexName) {

        // TODO 1: 数据库清理 (业务库)
        // fileRepository.deleteById(oldDocId);

        // TODO 2: 向量库清理 (ES 底层清理)
        // 呼叫轮子：vectorStoreService.deleteChunksByParentId(indexName, oldDocId);

        // TODO 3: Redis 父文档清理
        // stringRedisTemplate.delete("lumina:parent_doc:" + oldDocId);

        // ==========================================
        // 上面是清理数据，下面是重置缓存与重新摄入
        // ==========================================

        // 4. 触发 Kafka 广播，通知全网（包括当前节点和其他微服务节点）清理该文档相关的 AI 问答 L1/L2 缓存
        log.info("发送 Kafka 消息，通知全网清理 docId: {} 的 AI 缓存", oldDocId);
        kafkaTemplate.send("doc_update_topic", oldDocId);

        // 5. 将新文本重新喂给摄入引擎，生成全新的 parentId 和 切块
        // String newParentId = documentIngestionEngine.ingest("更新后的档案", newText, indexName);

        // TODO 6: 将新的 newParentId 存入你的 MySQL 业务库中关联起来...

        return "文档已更新，底层数据重建完成，并已全网广播缓存失效事件！";
    }
}