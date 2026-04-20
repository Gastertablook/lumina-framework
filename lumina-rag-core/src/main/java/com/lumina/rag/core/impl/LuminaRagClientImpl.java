package com.lumina.rag.core.impl;

import com.lumina.rag.core.cache.SemanticCacheManager;
import com.lumina.rag.core.concurrent.RequestDeduplicator;
import com.lumina.rag.core.config.LuminaAsyncConfig;
import com.lumina.rag.core.spi.LuminaRagClient;
import com.lumina.rag.core.spi.VectorStoreService;
import com.lumina.rag.core.domain.DocumentChunk;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.StreamingResponseHandler;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * 驾驭层：RAG 核心编排引擎 (总网关)
 * 这里集成了：缓存拦截 -> 并发防击穿 -> 向量检索 -> CoT组装 -> SSE推流 的全生命周期！
 */
@Slf4j
@Service
public class LuminaRagClientImpl implements LuminaRagClient {

    private final SemanticCacheManager cacheManager;
    private final RequestDeduplicator deduplicator;
    private final VectorStoreService vectorStoreService;
    private final StreamingChatLanguageModel streamingChatModel;

    private final EmbeddingModel embeddingModel;

    private final Executor ragExecutor;

    private final org.springframework.data.redis.core.StringRedisTemplate stringRedisTemplate;

    public LuminaRagClientImpl(
            SemanticCacheManager cacheManager,
            RequestDeduplicator deduplicator,
            VectorStoreService vectorStoreService,
            StreamingChatLanguageModel streamingChatModel,
            EmbeddingModel embeddingModel,
            org.springframework.data.redis.core.StringRedisTemplate stringRedisTemplate,
            @Qualifier(LuminaAsyncConfig.RAG_EXECUTOR_NAME) Executor ragExecutor) {
        this.cacheManager = cacheManager;
        this.deduplicator = deduplicator;
        this.vectorStoreService = vectorStoreService;
        this.streamingChatModel = streamingChatModel;
        this.embeddingModel = embeddingModel;
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

        // 1. 尝试从 L1/L2 多级缓存获取
        String cachedResponse = cacheManager.getCache(query, queryVector);
        if (cachedResponse != null) {
            log.info("[驾驭层] 缓存命中，直接使用静态 SSE 推流返回");
            sendCacheToSse(emitter, cachedResponse);
            return emitter;
        }

        // 注意：这里由于 SSE 是异步流式的，传统的 CompletableFuture 阻塞式防击穿需要变种。
        // 【架构红线】：必须在这里异步！保证 Tomcat 线程立刻释放，将阻塞风险转移至专属池去跑检索和推流。
        CompletableFuture.runAsync(() -> {
            try {
                String deduplicationKey = "llm_chat:" + DigestUtils.md5DigestAsHex(query.getBytes(StandardCharsets.UTF_8));
                AtomicBoolean isPioneer = new AtomicBoolean(false);

                // 4. 触发 Singleflight 并发护城河！
                String finalAnswer = deduplicator.execute(deduplicationKey, () -> {
                    isPioneer.set(true);
                    log.info("[驾驭层] 护城河放行先锋请求，开始真实检索与生成: {}", query);

                    // TODO: 【里程碑三/四扩展点】接入双层 LLM 与 Agent 意图识别
                    // String intent = agentRouter.extractIntent(query);
                    // if(intent.equals("summary")) { ...走长文档总结逻辑... }

                    // A. 执行终极混合检索
                    List<DocumentChunk> chunks = vectorStoreService.hybridSearch(
                            indexName, query, queryVector, metadataFilters, 3);

                    // ==========================================
                    // 【Small-to-Big 长上下文溯源】
                    // ==========================================
                    String context;
                    // 1. 从命中的碎片中，提取出所有的父文档 ID (去重)
                    java.util.Set<String> parentIds = chunks.stream()
                            .map(chunk -> (String) chunk.getMetadata().get("parentId"))
                            .filter(java.util.Objects::nonNull)
                            .collect(Collectors.toSet());

                    // 如果有父文档，缓存血缘就绑定父文档ID！否则降级绑定碎片ID！
                    // 用三元运算符一次性完成赋值，并且加上 final 关键字锁死！
                    final java.util.List<String> finalRefDocIds = parentIds.isEmpty()
                            ? chunks.stream().map(DocumentChunk::getChunkId).collect(Collectors.toList())
                            : new java.util.ArrayList<>(parentIds);

                    if (!parentIds.isEmpty()) {
                        log.info("[驾驭层] 触发 Small-to-Big 溯源！发现关联父文档数量: {}", parentIds.size());
                        java.util.List<String> parentTexts = new java.util.ArrayList<>();
                        for (String pid : parentIds) {
                            // 2. 顺藤摸瓜，去 Redis 提取那几万字的完整巨兽！
                            String parentDoc = stringRedisTemplate.opsForValue().get("lumina:parent_doc:" + pid);
                            if (parentDoc != null) {
                                parentTexts.add(parentDoc);
                            }
                        }
                        context = String.join("\n\n---\n\n", parentTexts);
                        log.info("[驾驭层] 上下文从碎片扩展至超大尺度！喂给大模型的文本量激增至: {} 字！", context.length());
                    } else {
                        // 3. 兼容老数据：如果没有父文档烙印，降级为传统的碎片拼接
                        context = chunks.stream().map(DocumentChunk::getText).collect(Collectors.joining("\n---\n"));
                        log.info("[驾驭层] 未发现父文档烙印，使用传统细粒度切块作为上下文。");
                    }

                    // B. 拼装 CoT 模板
                    String prompt = buildCoTPrompt(query, context);

                    // C. 桥接 LangChain4j 流式与 Singleflight 等待
                    CompletableFuture<String> llmFuture = new CompletableFuture<>();
                    StringBuilder fullResponse = new StringBuilder();

                    streamingChatModel.generate(prompt, new StreamingResponseHandler<AiMessage>() {
                        @Override
                        public void onNext(String token) {
                            try {
                                emitter.send(SseEmitter.event().data(token));
                                fullResponse.append(token);
                            } catch (IOException e) {
                                log.error("先锋请求 SSE 推流异常", e);
                            }
                        }

                        @Override
                        public void onComplete(Response<AiMessage> response) {
                            try {
                                emitter.send(SseEmitter.event().name("DONE").data("[DONE]"));
                                emitter.complete();
                            } catch (Exception e) {}

                            cacheManager.putCache(query, queryVector, fullResponse.toString(), finalRefDocIds);
                            // 瞬间唤醒在门外挂起的跟随者
                            llmFuture.complete(fullResponse.toString());
                        }

                        @Override
                        public void onError(Throwable error) {
                            emitter.completeWithError(error);
                            llmFuture.completeExceptionally(error);
                        }
                    });

                    try {
                        // 阻塞等待当前先锋流式生成完毕
                        return llmFuture.get();
                    } catch (Exception e) {
                        throw new RuntimeException("大模型流式调用中断", e);
                    }
                });

                // 5. 【跟随者收割逻辑】
                if (!isPioneer.get()) {
                    log.info("[驾驭层] 护城河拦截成功，跟随者醒来，直接下发复用成果: {}", query);
                    sendCacheToSse(emitter, finalAnswer);
                }
            } catch (Exception e) {
                log.error("RAG 异步流式处理异常", e);
                emitter.completeWithError(e);
            }
        }, ragExecutor); // 关键：指定了我们自己的线程池！

        return emitter;
    }

    /**
     * 固化在框架里的 CoT 防幻觉模板 (驾驭红线)
     */
    private String buildCoTPrompt(String query, String context) {
        return "你是一个专业、严谨的AI助手。请严格遵循以下【三步思考法】(Chain of Thought)来回答问题：\n" +
                "1. 仔细阅读我为你提供的【参考资料】。\n" +
                "2. 从资料中提取与用户问题直接相关的事实。\n" +
                "3. 仅基于提取的事实给出客观回答。\n" +
                "【架构红线】：如果提供的参考资料为空，或者资料中没有包含答案，请坚决回复'根据现有的参考资料，我无法回答该问题。'，绝不允许凭借自身记忆捏造事实！\n\n" +
                "【参考资料】:\n" + context + "\n\n" +
                "【用户问题】: " + query;
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