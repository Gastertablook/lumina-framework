package com.lumina.rag.core.config;

import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.elasticsearch.repository.config.EnableElasticsearchRepositories;

/**
 * 驾驭层：核心轮子的自动装配入口。
 * 只要有应用引入了 lumina-rag-core，就会自动扫描并注册所有的底层引擎组件。
 */
@Configuration
@ComponentScan(basePackages = {
        "com.lumina.rag.core.impl",
        "com.lumina.rag.core.cache",
        "com.lumina.rag.core.concurrent"
})
// 告诉 Spring Data 去哪里找我们的 ES Repository 接口
@EnableElasticsearchRepositories(basePackages = "com.lumina.rag.core.cache")
public class LuminaRagAutoConfiguration {
    // 未来如果需要注入大模型 API Key 等参数，也会在这里读取
}