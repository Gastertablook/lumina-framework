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
                // 必须绑定 sessionId，防止不同用户卡住对方检索！
                String deduplicationKey = "llm_chat:" + DigestUtils.md5DigestAsHex((sessionId + ":" + query).getBytes(StandardCharsets.UTF_8));
                AtomicBoolean isPioneer = new AtomicBoolean(false);

                // 触发 Singleflight 并发护城河！
                String finalAnswer = deduplicator.execute(deduplicationKey, () -> {
                    isPioneer.set(true);
                    log.info("[驾驭层] 护城河放行先锋请求，开始真实检索与生成: {}", query);

                    // ==========================================
                    // 【实例化装配】
                    // 把路由参数和空血缘集合，直接当做参数塞进新对象的肚子里！
                    // ==========================================
                    List<String> sessionRefDocIds = new ArrayList<>();
                    InformationRetrievalTool sessionTool = new InformationRetrievalTool(
                            vectorStoreService, embeddingModel, stringRedisTemplate, cacheManager,
                            indexName, metadataFilters, sessionRefDocIds
                    );

                    // 在请求发生的一瞬间，为这个用户秒建一个专属的 Agent 大脑！极度轻量且绝对安全！
                    LuminaAgentBrain sessionBrain = AiServices.builder(LuminaAgentBrain.class)
                            .streamingChatLanguageModel(streamingChatModel)
                            // 使用 MessageWindowChatMemory 配合 Redis 存储，实现滑动窗口的分布式漫游！
                            .chatMemoryProvider(memoryId -> MessageWindowChatMemory.builder()
                                    .id(memoryId)
                                    .maxMessages(10) // 只记最近 10 条，防止 Token 撑爆
                                    .chatMemoryStore(chatMemoryStore) // 强行挂载 Redis 存储引擎！
                                    .build())
                            .tools(sessionTool) // 把装满参数的专属 Tool 喂给当次大脑！
                            .build();

                    // 桥接 LangChain4j 流式与 Singleflight 等待
                    CompletableFuture<String> llmFuture = new CompletableFuture<>();
                    StringBuilder fullResponse = new StringBuilder();

                    // 【高潮】：不再手写查库逻辑，直接呼叫大脑！
                    // 大脑会自动去调 Tool，自动把长上下文塞进 prompt，最后流式返回给我们！
                    sessionBrain.chat(sessionId, query)
                            .onNext(token -> {
                                try {
                                    emitter.send(SseEmitter.event().data(token));
                                    fullResponse.append(token);
                                } catch (IOException e) {
                                    log.error("流推异常", e);
                                }
                            })
                            .onComplete(response -> {
                                try {
                                    emitter.send(SseEmitter.event().name("DONE").data("[DONE]"));
                                    emitter.complete();
                                } catch (Exception e) {}
                                // 这里的 sessionRefDocIds 已经被内部 Tool 在后台悄悄填满了！神不知鬼不觉！

                                llmFuture.complete(fullResponse.toString());
                            })
                            .onError(error -> {
                                emitter.completeWithError(error);
                                llmFuture.completeExceptionally(error);
                            })
                            .start();

                    try {
                        return llmFuture.get();
                    } catch (Exception e) {
                        throw new RuntimeException("Agent 思考执行中断", e);
                    }
                });
                if (!isPioneer.get()) {
                    log.info("[驾驭层] 护城河拦截成功，跟随者醒来，直接下发复用成果!");
                    sendCacheToSse(emitter, finalAnswer);
                }
            } catch (Exception e) {
                log.error("RAG Agent 异步流式处理异常", e);
                emitter.completeWithError(e);
            }
            // 关键：指定了我们自己的线程池！
        }, ragExecutor);

        return emitter;
    }

    private void sendCacheToSse(SseEmitter emitter, String cachedResponse) {
        try {
            emitter.send(SseEmitter.event().data(cachedResponse));
            emitter.send(SseEmitter.event().name("DONE").data("[DONE]"));
            emitter.complete();
        } catch (IOException e) {
            emitter.completeWithError(e);
        }
    }
}