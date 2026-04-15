package com.lumina.rag.core.config;

import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.AllMiniLmL6V2EmbeddingModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class LuminaEmbeddingConfig {

    @Bean
    public EmbeddingModel embeddingModel() {
        // 加载本地模型，第一次运行会自动下载（约20MB），之后使用缓存
        return new AllMiniLmL6V2EmbeddingModel();
    }
}