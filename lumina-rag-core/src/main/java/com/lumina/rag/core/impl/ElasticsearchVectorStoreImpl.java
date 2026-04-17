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
        log.info("🚀 [驾驭层] 开始向动态索引 [{}] 写入 {} 条 DocumentChunk", indexName, chunks.size());

        List<IndexQuery> queries = chunks.stream().map(chunk -> {
            EsDocDto dto = new EsDocDto(chunk.getChunkId(), chunk.getText(), chunk.getVector(), chunk.getMetadata());
            return new IndexQueryBuilder()
                    .withId(chunk.getChunkId())
                    .withObject(dto) // 利用 Spring Data ES 的自动序列化
                    .build();
        }).collect(Collectors.toList());

        // 使用 IndexCoordinates 实现动态索引名写入
        elasticsearchRestTemplate.bulkIndex(queries, IndexCoordinates.of(indexName));
        log.info("✅ [驾驭层] 动态索引 [{}] 写入完成！", indexName);
    }

    @Override
    public List<DocumentChunk> hybridSearch(String indexName, String queryText, List<Float> queryVector, Map<String, Object> filterConditions, int topK) {
        log.info("触发 Lumina 混合双引擎检索, Index: {}, Query: {}", indexName, queryText);

        // 1. 词法防线 （Operator.AND，绝不允许跨界幻觉）
        BoolQueryBuilder boolQuery = QueryBuilders.boolQuery();
        boolQuery.must(QueryBuilders.matchQuery("text", queryText).operator(org.elasticsearch.index.query.Operator.AND));

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
            log.warn("🛡️ [驾驭层] 检索空间/索引 [{}] 尚未创建或为空，触发优雅降级，返回空检索结果。", indexName);
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
}