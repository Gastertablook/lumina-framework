package com.lumina.rag.core.impl;

import com.lumina.rag.core.domain.DocumentChunk;
import com.lumina.rag.core.spi.VectorStoreService;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.elasticsearch.index.query.BoolQueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.index.query.functionscore.ScriptScoreFunctionBuilder;
import org.elasticsearch.script.Script;
import org.elasticsearch.script.ScriptType;
import org.springframework.data.annotation.Id;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.elasticsearch.core.ElasticsearchRestTemplate;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates;
import org.springframework.data.elasticsearch.core.query.IndexQuery;
import org.springframework.data.elasticsearch.core.query.IndexQueryBuilder;
import org.springframework.data.elasticsearch.core.query.NativeSearchQuery;
import org.springframework.data.elasticsearch.core.query.NativeSearchQueryBuilder;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ElasticsearchVectorStoreImpl implements VectorStoreService {

    private final ElasticsearchRestTemplate elasticsearchRestTemplate;

    /**
     * 内部 DTO：专门用来映射 ES 查询出来的自由字段。
     * 因为我们不知道上层应用会传什么 indexName，所以用这个动态实体接住 ES 返回的数据，避免强转异常。
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class EsDocDto {
        @Id
        private String chunkId;
        private String text;
        private List<Float> vector;
        private Map<String, Object> metadata;
    }

    @Override
    public void saveChunks(String indexName, List<DocumentChunk> chunks) {
        // 批量保存入 ES 的逻辑（使用 chunks 的数据）
        log.info("[驾驭层] 开始向动态索引 [{}] 写入 {} 条 DocumentChunk", indexName, chunks.size());

        // ==========================================
        // 【驾驭工程：基建自动化】强制接管并约束底层向量数据结构！
        // 绝对不允许 ES 瞎猜类型，必须是 dense_vector 和 ik_max_word！
        // ==========================================
        org.springframework.data.elasticsearch.core.IndexOperations indexOps = elasticsearchRestTemplate.indexOps(IndexCoordinates.of(indexName));
        if (!indexOps.exists()) {
            log.info("发现新索引 [{}]，正在执行自动化基建 (创建索引与稠密向量映射)...", indexName);
            indexOps.create();
            // 强制规定 vector 为 384 维余弦相似度稠密向量，text 使用 IK 分词器！
            String mapping = "{\"properties\":{\"vector\":{\"type\":\"dense_vector\",\"dims\":384,\"index\":true,\"similarity\":\"cosine\"},\"text\":{\"type\":\"text\",\"analyzer\":\"ik_max_word\"}}}";
            // 注意：这里使用全限定类名，防止与 LangChain4j 的 Document 冲突
            indexOps.putMapping(org.springframework.data.elasticsearch.core.document.Document.parse(mapping));
            log.info("索引 [{}] 物理骨架搭建完毕！", indexName);
        }

        List<IndexQuery> queries = chunks.stream().map(chunk -> {
            EsDocDto dto = new EsDocDto(chunk.getChunkId(), chunk.getText(), chunk.getVector(), chunk.getMetadata());
            return new IndexQueryBuilder()
                    .withId(chunk.getChunkId())
                    .withObject(dto)
                    .build();
        }).collect(Collectors.toList());

        elasticsearchRestTemplate.bulkIndex(queries, IndexCoordinates.of(indexName));
        log.info("[驾驭层] 动态索引 [{}] 数据灌入完成！", indexName);
    }

    @Override
    public List<DocumentChunk> hybridSearch(String indexName, String queryText, List<Float> queryVector, Map<String, Object> filterConditions, int topK) {
        log.info("触发 Lumina 混合双引擎检索, Index: {}, Query: {}", indexName, queryText);

        // 1. 词法防线 （依靠大模型提取的高质量关键词进行默认 OR 匹配，彻底把“语义判断”的权力交还给下方的 HNSW 向量余弦算分引擎！）
        BoolQueryBuilder boolQuery = QueryBuilders.boolQuery();
        boolQuery.must(QueryBuilders.matchQuery("text", queryText));

        // 2. 权限/元数据硬过滤 (驾驭约束：动态剥离越权操作，确保不越权)
        if (filterConditions != null && !filterConditions.isEmpty()) {
            filterConditions.forEach((key, value) -> {
                boolQuery.filter(QueryBuilders.termQuery("metadata." + key, value));
            });
        }

        // 3. 语义防线 (Painless Script 余弦相似度算分)
        Map<String, Object> params = Collections.singletonMap("query_vector", queryVector);
        Script script = new Script(ScriptType.INLINE, "painless", "cosineSimilarity(params.query_vector, 'vector') + 1.0", params);
        ScriptScoreFunctionBuilder scriptScoreFunctionBuilder = new ScriptScoreFunctionBuilder(script);

        // 组装原生大查询
        NativeSearchQuery searchQuery = new NativeSearchQueryBuilder()
                .withQuery(QueryBuilders.functionScoreQuery(boolQuery, scriptScoreFunctionBuilder))
                .withPageable(PageRequest.of(0, topK))
                .build();

        SearchHits<EsDocDto> searchHits;
        try {
            searchHits = elasticsearchRestTemplate.search(searchQuery, EsDocDto.class, IndexCoordinates.of(indexName));
        } catch (org.springframework.data.elasticsearch.NoSuchIndexException e) {
            log.warn("🛡[驾驭层] 检索空间/索引 [{}] 尚未创建或为空，触发优雅降级，返回空检索结果。", indexName);
            return Collections.emptyList();
        } catch (Exception e) {
            log.error("混合检索发生未知异常", e);
            return Collections.emptyList();
        }

        // 重新打包为极其干净的 DocumentChunk 标准件返回
        List<DocumentChunk> result = new ArrayList<>();
        for (SearchHit<EsDocDto> hit : searchHits) {
            EsDocDto content = hit.getContent();
            DocumentChunk chunk = DocumentChunk.builder()
                    .chunkId(content.getChunkId())
                    .text(content.getText())
                    .vector(content.getVector())
                    .metadata(content.getMetadata())
                    .build();
            result.add(chunk);
        }

        return result;
    }

    @Override
    public void deleteChunksByParentId(String indexName, String parentId) {
        NativeSearchQuery deleteQuery = new NativeSearchQueryBuilder()
                .withQuery(QueryBuilders.termQuery("metadata.parentId", parentId))
                .build();
        elasticsearchRestTemplate.delete(deleteQuery, EsDocDto.class, IndexCoordinates.of(indexName));
        log.info("[驾驭层] 已从索引 [{}] 中物理删除关联 ParentID [{}] 的所有文档碎片！", indexName, parentId);
    }
}