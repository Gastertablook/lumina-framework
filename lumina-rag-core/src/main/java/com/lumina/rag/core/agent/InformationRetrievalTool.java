package com.lumina.rag.core.agent;

import com.lumina.rag.core.domain.DocumentChunk;
import com.lumina.rag.core.spi.VectorStoreService;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.model.embedding.EmbeddingModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;

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

    // 当前请求独占的参数
    private final String indexName;
    private final Map<String, Object> filters;
    private final List<String> refDocIds; // 引用传递，用于向外挂网关回写血缘！

    /**
     * 【神级注解 @Tool】：
     * 这段中文不仅是给开发者看的，更是发给大模型看的！
     * 大模型会通过这段描述，自己决定什么时候该触发这个 Java 方法，以及怎么从用户口语里提取精简的 keyword 参数！
     */
    @Tool("当你需要获取外部参考资料、私有数据、业务线索或长文本上下文时，必须调用此检索工具。请传入精简提炼后的核心搜索词（keyword）。")
    public String retrieveInformation(String keyword) {
        log.info("[Agent 大脑决断] 触发底层数据检索工具，大模型提取的检索词为: [{}]", keyword);

        try {
            // 向量化大模型提炼的关键词
            List<Float> queryVector = embeddingModel.embed(keyword).content().vectorAsList();

            // 执行精准混合检索
            List<DocumentChunk> chunks = vectorStoreService.hybridSearch(
                    indexName, keyword, queryVector, this.filters, 3);

            if (chunks == null || chunks.isEmpty()) {
                return "【系统警告】：底层数据引擎未检索到任何信息！你必须立刻停止作答，并原封不动地向用户回复：‘抱歉，当前私有数据空间中没有关于此问题的记载。’ 绝不允许进行任何猜测！";
            }

            // ==========================================
            // 【Small-to-Big 长上下文溯源】
            // ==========================================
            Set<String> parentIds = chunks.stream()
                    .map(chunk -> (String) chunk.getMetadata().get("parentId"))
                    .filter(java.util.Objects::nonNull)
                    .collect(Collectors.toSet());

            // 直接把血缘写入肚子里的 List，外面的网关无缝读取！
            if (parentIds.isEmpty()) {
                this.refDocIds.addAll(chunks.stream().map(DocumentChunk::getChunkId).collect(Collectors.toList()));
            } else {
                this.refDocIds.addAll(parentIds);
            }

            if (!parentIds.isEmpty()) {
                log.info("[Agent 工具] 触发 Small-to-Big 溯源...");
                List<String> parentTexts = new java.util.ArrayList<>();
                for (String pid : parentIds) {
                    String parentDoc = stringRedisTemplate.opsForValue().get("lumina:parent_doc:" + pid);
                    if (parentDoc != null) {
                        parentTexts.add(parentDoc);
                    }
                }
                String massiveContext = String.join("\n\n---\n\n", parentTexts);
                log.info("[Agent 工具] 成功提取 {} 字的巨量参考资料供大脑分析！", massiveContext.length());
                // 直接把万字长文当作“工具返回值”扔给大模型大脑！
                return massiveContext;
            } else {
                return chunks.stream().map(DocumentChunk::getText).collect(Collectors.joining("\n---\n"));
            }
        } catch (Exception e) {
            log.error("检索工具执行异常", e);
            return "系统异常，无法检索。";
        }
    }
}