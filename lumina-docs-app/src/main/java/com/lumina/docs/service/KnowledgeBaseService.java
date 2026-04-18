package com.lumina.docs.service;

import com.lumina.rag.core.domain.DocumentChunk;
import com.lumina.rag.core.spi.VectorStoreService;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeBaseService {

    // 直接呼叫我们的底层双引擎
    private final VectorStoreService vectorStoreService;
    private final EmbeddingModel embeddingModel;

    /**
     * 将长文本切块、向量化并灌入底层 ES
     */
    public void ingestText(String sourceName, String text, String indexName) {
        log.info("开始摄入文档: {}, 目标知识库: {}", sourceName, indexName);

        // 1. 使用 LangChain4j 的极简切块器 (每块 500 字，重叠 50 字防止上下文断裂)
        DocumentSplitter splitter = DocumentSplitters.recursive(500, 50);
        List<TextSegment> segments = splitter.split(Document.from(text));

        log.info("文档已切分为 {} 个 Chunk，准备向量化...", segments.size());

        // 2. 将纯文本段落转换为带向量的终极 DocumentChunk
        List<DocumentChunk> chunks = segments.stream().map(segment -> {
            // 实时生成 384 维向量 (0 成本本地执行)
            List<Float> vector = embeddingModel.embed(segment.text()).content().vectorAsList();

            // 组装元数据 (极其重要！未来权限过滤和 Small-to-Big 溯源全靠它)
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("sourceName", sourceName);
            metadata.put("ingestTime", System.currentTimeMillis());

            return DocumentChunk.builder()
                    .chunkId(UUID.randomUUID().toString())
                    .text(segment.text())
                    .vector(vector)
                    .metadata(metadata)
                    .build();
        }).collect(Collectors.toList());

        // 3. 一键丢给我们的底层轮子，存入 Elasticsearch！
        vectorStoreService.saveChunks(indexName, chunks);
        log.info("文档摄入彻底完成！");
    }
}