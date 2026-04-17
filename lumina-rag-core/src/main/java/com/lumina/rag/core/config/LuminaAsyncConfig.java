package com.lumina.rag.core.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 驾驭层：RAG 引擎专属的高并发线程池
 * 用于隔离大模型长耗时调用，防止拖垮整个 Tomcat Web 线程池。
 */
@Slf4j
@Configuration
public class LuminaAsyncConfig {

    public static final String RAG_EXECUTOR_NAME = "luminaRagExecutor";

    @Bean(name = RAG_EXECUTOR_NAME)
    public Executor luminaRagExecutor() {
        log.info("[驾驭层] 初始化 Lumina RAG 专属异步线程池...");
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        // 核心线程数：应对日常对话
        executor.setCorePoolSize(20);
        // 最大线程数：应对并发高峰
        executor.setMaxPoolSize(100);
        // 队列容量：缓冲等待的提问
        executor.setQueueCapacity(200);
        // 线程名称前缀，方便日志排查排错！
        executor.setThreadNamePrefix("LuminaRAG-");
        // 拒绝策略：CallerRunsPolicy（队列满了，谁调用的谁自己执行，防止丢消息，起到天然限流作用）
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        // 优雅停机：等任务跑完再关机
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.initialize();
        return executor;
    }
}