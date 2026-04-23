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

    public LuminaRagClientImpl(
            SemanticCacheManager cacheManager,
            RequestDeduplicator deduplicator,
            StreamingChatLanguageModel streamingChatModel,
            EmbeddingModel embeddingModel,
            VectorStoreService vectorStoreService,
            StringRedisTemplate stringRedisTemplate,
            @Qualifier(LuminaAsyncConfig.RAG_EXECUTOR_NAME) Executor ragExecutor) {
        this.cacheManager = cacheManager;
        this.deduplicator = deduplicator;
        this.streamingChatModel = streamingChatModel;
        this.embeddingModel = embeddingModel;
        this.vectorStoreService = vectorStoreService;
        this.stringRedisTemplate = stringRedisTemplate;
        this.ragExecutor = ragExecutor;
    }

    @Override
    public SseEmitter chatStream(String query, String sessionId, String indexName, Map<String, Object> metadataFilters) {
        // 创建 SSE 发射器 (超时设为 0，防止大模型思考过久断开)
        SseEmitter emitter = new SseEmitter(0L);

        // 实时计算提问的向量 (0成本毫秒级转换)
        List<Float> queryVector;
        try {
            queryVector = embeddingModel.embed(query).content().vectorAsList();
        } catch (Exception e) {
            log.error("Query向量化失败", e);
            emitter.completeWithError(e);
            return emitter;
        }

        // 尝试从 L1/L2 多级缓存获取
        String cachedResponse = cacheManager.getCache(indexName, query, queryVector);
        if (cachedResponse != null) {
            log.info("[驾驭层] 多级缓存命中，直接使用静态 SSE 推流返回");
            sendCacheToSse(emitter, cachedResponse);
            return emitter;
        }

        // 注意：这里由于 SSE 是异步流式的，传统的 CompletableFuture 阻塞式防击穿需要变种。
        // 【架构红线】：必须在这里异步！保证 Tomcat 线程立刻释放，将阻塞风险转移至专属池去跑检索和推流。
        CompletableFuture.runAsync(() -> {
            // 【架构防线：ConcurrentHashMap 上下文生命周期管理】
            try {
                String deduplicationKey = "llm_chat:" + DigestUtils.md5DigestAsHex(query.getBytes(StandardCharsets.UTF_8));
                AtomicBoolean isPioneer = new AtomicBoolean(false);

                // 触发 Singleflight 并发护城河！
                String finalAnswer = deduplicator.execute(deduplicationKey, () -> {
                    isPioneer.set(true);
                    log.info("[驾驭层] 护城河放行先锋请求，开始真实检索与生成: {}", query);

                    // ==========================================
                    // 【实例化装配】
                    // 彻底抛弃全局上下文！把路由参数和空血缘集合，直接当做参数塞进新对象的肚子里！
                    // ==========================================
                    List<String> sessionRefDocIds = new ArrayList<>();
                    InformationRetrievalTool sessionTool = new InformationRetrievalTool(
                            vectorStoreService, embeddingModel, stringRedisTemplate,
                            indexName, metadataFilters, sessionRefDocIds
                    );

                    // 在请求发生的一瞬间，为这个用户秒建一个专属的 Agent 大脑！极度轻量且绝对安全！
                    LuminaAgentBrain sessionBrain = AiServices.builder(LuminaAgentBrain.class)
                            .streamingChatLanguageModel(streamingChatModel)
                            .chatMemoryProvider(memoryId -> MessageWindowChatMemory.withMaxMessages(10))
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

                                cacheManager.putCache(indexName, query, queryVector, fullResponse.toString(), sessionRefDocIds);

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

                // 5. 【跟随者收割逻辑】
                if (!isPioneer.get()) {
                    log.info("[驾驭层] 护城河拦截成功，跟随者醒来，直接下发复用成果: {}", query);
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

    /**
     * 辅助方法：将命中缓存的整段字符串伪装成流式输出
     */
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