package com.lumina.rag.core.agent;

import com.lumina.rag.core.cache.SemanticCacheManager;
import com.lumina.rag.core.concurrent.RequestDeduplicator;
import com.lumina.rag.core.constant.LuminaConstants;
import com.lumina.rag.core.domain.DocumentChunk;
import com.lumina.rag.core.spi.VectorStoreService;
import com.lumina.rag.core.tracing.LuminaTracer;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.model.embedding.EmbeddingModel;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.util.DigestUtils;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 【驾驭层】Agent 智能体的第一件核心神兵：终极知识库检索器
 */
@Slf4j
@RequiredArgsConstructor
public class InformationRetrievalTool {

    // 底层服务组件
    private final VectorStoreService vectorStoreService;
    private final EmbeddingModel embeddingModel;
    private final StringRedisTemplate stringRedisTemplate;
    private final SemanticCacheManager cacheManager;
    //底层物理防线控制器
    private final RequestDeduplicator deduplicator;

    // 当前请求独占的参数
    private final String indexName;
    private final Map<String, Object> filters;
    private final List<String> refDocIds; // 引用传递，用于向外挂网关回写血缘！

    /**
     * 【驾驭工程：实体抽取指令】
     * 强迫大模型将自然语言转化为标准的搜索引擎 Keyword 格式！
     */
    @Tool("【强制调用】当用户询问客观事实或查阅资料时，必须调用此工具！参数要求：" +
            "1. keyword 提取核心名词（空格分隔）；" +
            "2. needLongContext：如果问题需要宏观总结、对比分析，设为 true；如果是查找特定名字、数值等细节，设为 false。")
    public String retrieveInformation(String keyword, boolean needLongContext) {
        // 【可观测性】创建追踪 Span
        Span span = LuminaTracer.start("lumina.retrieve")
                .setAttribute("keyword", keyword)
                .setAttribute("needLongContext", String.valueOf(needLongContext))
                .setAttribute("indexName", this.indexName);
        try (Scope scope = span.makeCurrent()) {
            log.info("[Agent 大脑决断] 触发底层数据检索工具，大模型提取的检索词为: [{}], 是否拉取巨型长文: [{}]", keyword, needLongContext);

            String result = doRetrieve(keyword, needLongContext);
            LuminaTracer.end(span);
            return result;
        } catch (Exception e) {
            log.error("检索工具执行异常", e);
            LuminaTracer.endWithError(span, e);
            return "系统异常，无法检索。";
        }
    }

    /**
     * 实际的检索逻辑，抽成独立方法以便追踪 Span 统一管理生命周期
     */
    private String doRetrieve(String keyword, boolean needLongContext) {
        try {
            // 1. 向量化大模型提炼的关键词
            List<Float> queryVector = embeddingModel.embed(keyword).content().vectorAsList();

            // ==========================================
            // 【工具级语义缓存】
            // 缓存的 Key 是最纯净的实体关键词！大模型下次只要提取出相似关键词，
            // 7ms 内直接返回长篇文档，根本不用再去查 ES 和 Redis！
            // ==========================================
            String cachedContext = cacheManager.getCache(this.indexName, keyword, queryVector);
            if (cachedContext != null) {
                log.info("[工具级缓存] 极速命中！直接返回底层客观知识上下文！");
                return cachedContext;
            }

            // ==========================================
            // 2. 【终极护城河：Tool 级 Singleflight】
            // 跨用户的海量相同检索请求，在这里被强制截停！
            // Key：库名 + 关键词 MD5（保证纯粹的客观检索去重）
            // ==========================================
            String deduplicationKey = "tool_retrieval:" + this.indexName + ":" + DigestUtils.md5DigestAsHex(keyword.getBytes(StandardCharsets.UTF_8));

            return deduplicator.execute(deduplicationKey, () -> {
                // 3. 【双重检查锁 (Double-Check)】
                // 当跟随者被唤醒后，先锋大概率已经把缓存写好了，必须再查一次，防止击穿底层！
                String doubleCheckCache = cacheManager.getCache(this.indexName, keyword, queryVector);
                if (doubleCheckCache != null) {
                    log.info("[工具级护城河] 跟随者苏醒，成功复用先锋写入的缓存数据！");
                    return doubleCheckCache;
                }

                log.info("[工具级护城河] 先锋线程出击！执行真实的 ES 检索与长文溯源！");

                // 4. 缓存未命中，执行精准混合检索
                List<DocumentChunk> chunks = vectorStoreService.hybridSearch(
                        indexName, keyword, queryVector, this.filters, 3);

                if (chunks == null || chunks.isEmpty()) {
                    return "【系统警告】：底层数据引擎未检索到任何信息！你必须立刻停止作答，并原封不动地向用户回复：‘抱歉，当前私有数据空间中没有关于此问题的记载。’ ";
                }

                // ==========================================
                // 【Small-to-Big 长上下文溯源】
                // ==========================================
                Set<String> parentIds = chunks.stream()
                        .map(chunk -> (String) chunk.getMetadata().get(LuminaConstants.FIELD_PARENT_ID))
                        .filter(java.util.Objects::nonNull)
                        .collect(Collectors.toSet());

                // 直接把血缘写入肚子里的 List，外面的网关无缝读取！
                if (parentIds.isEmpty()) {
                    this.refDocIds.addAll(chunks.stream().map(DocumentChunk::getChunkId).collect(Collectors.toList()));
                } else {
                    this.refDocIds.addAll(parentIds);
                }

                // Small-to-Big 组装上下文
                String retrievedData;
                if (needLongContext && !parentIds.isEmpty()) {
                    log.info("[Agent 工具] 触发 Small-to-Big 溯源...");
                    List<String> parentTexts = new java.util.ArrayList<>();
                    for (String pid : parentIds) {
                        String parentDoc = stringRedisTemplate.opsForValue().get(LuminaConstants.PARENT_DOC_PREFIX + pid);
                        if (parentDoc != null) {
                            parentTexts.add(parentDoc);
                        }
                    }
                    retrievedData = String.join("\n\n---\n\n", parentTexts);
                    log.info("[Agent 工具] 成功提取 {} 字的巨量参考资料供大脑分析！", retrievedData.length());
                } else {
                    log.info("[Agent 工具] 大模型判定为细节问题，仅使用高精度碎片 (Short RAG)...");
                    retrievedData = chunks.stream().map(DocumentChunk::getText).collect(Collectors.joining("\n---\n"));
                }

                // ==========================================
                // 【写入工具级缓存】
                // 将纯粹的“事实上下文”写入缓存！
                // ==========================================
                cacheManager.putCache(this.indexName, keyword, queryVector, retrievedData, this.refDocIds);

                return retrievedData ;
            });
        } catch (Exception e) {
            log.error("检索工具执行异常", e);
            return "系统异常，无法检索。";
        }
    }
}