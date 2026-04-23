package com.lumina.rag.core.entity;

import lombok.Builder;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;
import org.springframework.data.elasticsearch.annotations.Similarity;

import java.util.List;

/**
 * L2 语义缓存 ES 实体 (去业务化)
 */
@Data
@Builder
@Document(indexName = "lumina_semantic_cache") // 索引改名，彰显框架独立性
public class SemanticCacheEntity {

    @Id
    private String id;

    // 词法防线：使用 IK 分词
    @Field(type = FieldType.Text, analyzer = "ik_max_word", searchAnalyzer = "ik_smart")
    private String queryText;

    // 语义防线：384维稠密向量 (适配你的 all-minilm-l6-v2)
    @Field(type = FieldType.Dense_Vector, dims = 384, index = true)
    private List<Float> queryVector;

    // 缓存的大模型回答
    @Field(type = FieldType.Text, index = false)
    private String llmResponse;

    // 泛化的文档片段 IDs
    // 用于 Kafka 监听到某个文档/PDF更新时，顺藤摸瓜炸毁缓存
    @Field(type = FieldType.Keyword)
    private List<String> refDocIds;

    @Field(type = FieldType.Long)
    private Long createTime;

    // 租户隔离字段，防止不同知识库的缓存串库
    @Field(type = FieldType.Keyword)
    private String indexName;
}