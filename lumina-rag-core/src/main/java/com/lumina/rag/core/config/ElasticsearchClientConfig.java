package com.lumina.rag.core.config;

import org.elasticsearch.client.RestHighLevelClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.elasticsearch.client.ClientConfiguration;
import org.springframework.data.elasticsearch.client.RestClients;
import org.springframework.data.elasticsearch.core.ElasticsearchRestTemplate;
import org.springframework.http.HttpHeaders;

/**
 * 驾驭层：ES 高级客户端兼容性配置。
 * 强制使用兼容模式，完美抹平 Spring Boot 2.7 (ES 7.x Client) 与 ES 8.x Server 的代沟！
 */
@Configuration
public class ElasticsearchClientConfig {

    @Bean
    public RestHighLevelClient restHighLevelClient() {
        // 构建兼容性请求头，欺骗 ES 8，让它以 ES 7 的格式返回响应体
        HttpHeaders compatibilityHeaders = new HttpHeaders();
        compatibilityHeaders.add("Accept", "application/vnd.elasticsearch+json;compatible-with=7");
        compatibilityHeaders.add("Content-Type", "application/vnd.elasticsearch+json;compatible-with=7");

        ClientConfiguration clientConfiguration = ClientConfiguration.builder()
                .connectedTo("127.0.0.1:9200")
                .withDefaultHeaders(compatibilityHeaders)
                .build();

        return RestClients.create(clientConfiguration).rest();
    }

    /**
     * 显式提供一个 ElasticsearchRestTemplate 的 Bean！
     * 精准满足 SemanticCacheManager 和 ElasticsearchVectorStoreImpl 的注入需求，
     * 踢开 Spring Boot 喜欢瞎猜的自动装配！
     * 给它贴上两个名字，满足 SemanticCacheManager(按类型匹配) 和 Repository(按 elasticsearchTemplate 名字匹配) 的双重需求！
     */
    @Bean(name = {"elasticsearchTemplate", "elasticsearchRestTemplate"})
    public ElasticsearchRestTemplate elasticsearchRestTemplate(RestHighLevelClient client) {
        return new ElasticsearchRestTemplate(client);
    }
}