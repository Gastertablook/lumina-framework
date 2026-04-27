package com.lumina.rag.core.impl;

import com.lumina.rag.core.constant.LuminaConstants;
import com.lumina.rag.core.domain.DocumentChunk;
import com.lumina.rag.core.spi.DocumentIngestionEngine;
import com.lumina.rag.core.spi.DocumentSplitterStrategy;
import com.lumina.rag.core.spi.VectorStoreService;
import dev.langchain4j.model.embedding.EmbeddingModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentIngestionEngineImpl implements DocumentIngestionEngine {

    private final VectorStoreService vectorStoreService;
    private final EmbeddingModel embeddingModel;
    private final StringRedisTemplate stringRedisTemplate;
    private final DocumentSplitterStrategy splitterStrategy;

    @Override
    public String ingest(String sourceName, String text, String indexName) {
        String parentId = "doc_" + UUID.randomUUID().toString();

        // 1. 骨架逻辑：框架强行接管父文档存储
        stringRedisTemplate.opsForValue().set(LuminaConstants.PARENT_DOC_PREFIX + parentId, text, 30, TimeUnit.DAYS);

        // 2. 策略委派：调用外部传入的切块策略获取文本碎片
        List<String> textChunks = splitterStrategy.split(text);

        // 3. 骨架逻辑：框架统一打上防篡改烙印，并执行向量化
        List<DocumentChunk> chunks = textChunks.stream().map(chunkText -> {
            List<Float> vector = embeddingModel.embed(chunkText).content().vectorAsList();
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("sourceName", sourceName);
            metadata.put(LuminaConstants.FIELD_PARENT_ID, parentId); // 绝对安全的烙印
            return DocumentChunk.builder()
                    .chunkId(UUID.randomUUID().toString())
                    .text(chunkText)
                    .vector(vector)
                    .metadata(metadata)
                    .build();
        }).collect(Collectors.toList());

        vectorStoreService.saveChunks(indexName, chunks);
        return parentId;
    }

    @Override
    public void deleteParentDoc(String parentId) {
        stringRedisTemplate.delete(LuminaConstants.PARENT_DOC_PREFIX + parentId);
    }

    @Override
    public void removeDocument(String indexName, String parentId) {
        log.info("[驾驭层] 接到业务指令，开始物理销毁底层关联数据, ParentID: {}", parentId);
        // 删 ES 碎片
        vectorStoreService.deleteChunksByParentId(indexName, parentId);
        // 删 Redis 长文本
        stringRedisTemplate.delete(LuminaConstants.PARENT_DOC_PREFIX + parentId);
    }
}