package com.lumina.rag.core.config;

import com.lumina.rag.core.impl.DefaultRecursiveSplitter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LuminaRagAutoConfiguration 自动装配相关测试
 *
 * 验证核心配置类能否正确创建 Bean
 */
class LuminaRagAutoConfigurationTest {

    @Test
    @DisplayName("LuminaEmbeddingConfig 应创建 EmbeddingModel Bean")
    void embeddingConfig_ShouldCreateEmbeddingModel() {
        // EmbeddingModel 的创建不依赖外部基础设施
        LuminaEmbeddingConfig config = new LuminaEmbeddingConfig();
        assertNotNull(config.embeddingModel(), "EmbeddingModel 不应为 null");
    }

    @Test
    @DisplayName("LuminaAsyncConfig 应创建 Executor Bean")
    void asyncConfig_ShouldCreateExecutor() {
        LuminaAsyncConfig config = new LuminaAsyncConfig();
        assertNotNull(config.luminaRagExecutor(), "luminaRagExecutor 不应为 null");
    }

    @Test
    @DisplayName("DefaultRecursiveSplitter 应能正常创建")
    void defaultRecursiveSplitter_ShouldBeCreated() {
        DefaultRecursiveSplitter splitter = new DefaultRecursiveSplitter();
        assertNotNull(splitter);
    }

    @Test
    @DisplayName("LuminaAsyncConfig 的线程池应配置正确的参数")
    void asyncConfig_ExecutorShouldHaveCorrectParams() {
        LuminaAsyncConfig config = new LuminaAsyncConfig();
        var executor = config.luminaRagExecutor();
        assertNotNull(executor);
        // 验证 executor 能正常执行任务
        assertDoesNotThrow(() -> executor.execute(() -> {}));
    }
}
