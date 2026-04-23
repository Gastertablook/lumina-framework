package com.lumina.docs.service;

import com.lumina.rag.core.spi.DocumentIngestionEngine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 业务层知识库服务
 * 现在它薄得像一张纸，所有的脏活累活全被底层轮子 (DocumentIngestionEngine) 包揽了！
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeBaseService {

    // 【核心】：仅仅注入在轮子里造好的摄入引擎 SPI
    private final DocumentIngestionEngine documentIngestionEngine;

    public String ingestText(String sourceName, String text, String indexName) {
        log.info("业务层收到文档 [{}], 准备委托给核心轮子进行黑盒摄入...", sourceName);

        // 核心轮子接管：自动存Redis、自动切块策略、自动打烙印、自动存ES！
        // 并且返回绝对安全的 parentId
        String parentId = documentIngestionEngine.ingest(sourceName, text, indexName);

        log.info("业务层摄入完成！拿到核心引擎返回的 ParentID: {}", parentId);
        return parentId;
    }
}