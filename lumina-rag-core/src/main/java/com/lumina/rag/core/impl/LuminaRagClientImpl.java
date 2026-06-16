package com.lumina.rag.core.impl;

import com.lumina.rag.core.agent.InformationRetrievalTool;
import com.lumina.rag.core.agent.LuminaAgentBrain;
import com.lumina.rag.core.cache.SemanticCacheManager;
import com.lumina.rag.core.concurrent.RequestDeduplicator;
import com.lumina.rag.core.config.LuminaAsyncConfig;
import com.lumina.rag.core.spi.LuminaRagClient;
import com.lumina.rag.core.spi.VectorStoreService;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.service.AiServices;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 驾驭层：RAG 核心编排引擎 (总网关)
 * 这里集成了：缓存拦截 -> 并发防击穿 -> 向量检索 -> CoT组装 -> SSE推流 的全生命周期！
 */
@Slf4j
@Service
public class LuminaRagClientImpl implements LuminaRagClient {

    private final SemanticCacheManager cacheManager;
    private final RequestDeduplicator deduplicator;
    private final StreamingChatLanguageModel streamingChatModel;

    private final EmbeddingModel embeddingModel;

    private final Executor ragExecutor;

    private final VectorStoreService vectorStoreService;
    private final StringRedisTemplate stringRedisTemplate;

    private final dev.langchain4j.store.memory.chat.ChatMemoryStore chatMemoryStore;

    public LuminaRagClientImpl(
            SemanticCacheManager cacheManager,
            RequestDeduplicator deduplicator,
            StreamingChatLanguageModel streamingChatModel,
            EmbeddingModel embeddingModel,
            VectorStoreService vectorStoreService,
            StringRedisTemplate stringRedisTemplate,
            dev.langchain4j.store.memory.chat.ChatMemoryStore chatMemoryStore,
            @Qualifier(LuminaAsyncConfig.RAG_EXECUTOR_NAME) Executor ragExecutor) {
        this.cacheManager = cacheManager;
        this.deduplicator = deduplicator;
        this.streamingChatModel = streamingChatModel;
        this.embeddingModel = embeddingModel;
        this.vectorStoreService = vectorStoreService;
        this.stringRedisTemplate = stringRedisTemplate;
        this.chatMemoryStore = chatMemoryStore;
        this.ragExecutor = ragExecutor;
    }

    @Override
    public SseEmitter chatStream(String query, String sessionId, String indexName, Map<String, Object> metadataFilters) {
        // 创建 SSE 发射器 (超时设为 0，防止大模型思考过久断开)
        SseEmitter emitter = new SseEmitter(0L);

        // 注意：这里由于 SSE 是异步流式的，传统的 CompletableFuture 阻塞式防击穿需要变种。
        // 【架构红线】：必须在这里异步！保证 Tomcat 线程立刻释放，将阻塞风险转移至专属池去跑检索和推流。
        CompletableFuture.runAsync(() -> {
            try {
                log.info("[网关层] 接收请求，全量放行至 Agent 大脑...");

                List<String> sessionRefDocIds = new ArrayList<>();
                // 【架构升维】：把防击穿护城河 deduplicator 下沉注入给 Tool！
                InformationRetrievalTool sessionTool = new InformationRetrievalTool(
                        vectorStoreService, embeddingModel, stringRedisTemplate, cacheManager, deduplicator,
                        indexName, metadataFilters, sessionRefDocIds
                );

                LuminaAgentBrain sessionBrain = AiServices.builder(LuminaAgentBrain.class)
                        .streamingChatLanguageModel(streamingChatModel)
                        .chatMemoryProvider(memoryId -> MessageWindowChatMemory.builder()
                                .id(memoryId).maxMessages(10).chatMemoryStore(chatMemoryStore).build())
                        .tools(sessionTool)
                        .build();

                sessionBrain.chat(sessionId, query)
                        .onNext(token -> {
                            try {
                                emitter.send(SseEmitter.event().data(token));
                            } catch (IOException e) {
                                log.error("流推异常", e);
                            }
                        })
                        .onComplete(response -> {
                            try {
                                emitter.send(SseEmitter.event().name("DONE").data("[DONE]"));
                                emitter.complete();
                            } catch (Exception e) {}
                        })
                        .onError(error -> emitter.completeWithError(error))
                        .start();
            } catch (Exception e) {
                log.error("RAG Agent 异步流式处理异常", e);
                emitter.completeWithError(e);
            }
            // 关键：指定了我们自己的线程池！
        }, ragExecutor);

        return emitter;
    }
}