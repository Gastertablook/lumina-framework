package com.lumina.mcp.server.tool;

import com.lumina.mcp.server.protocol.JsonRpcMessage.ToolDefinition;
import com.lumina.mcp.server.protocol.JsonRpcMessage.ToolCallResult;
import com.lumina.rag.core.agent.InformationRetrievalTool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MCP 工具注册中心
 * 将 Lumina RAG 引擎的 InformationRetrievalTool 包装为 MCP 标准工具。
 *
 * 设计思路：
 * - 每个 MCP 会话可以创建独立的 Tool 实例（不同 indexName/filters）
 * - 通过 tools/list 暴露工具定义
 * - 通过 tools/call 执行工具调用
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LuminaMcpToolRegistry {

    // 默认工具定义（不依赖 Spring Bean，纯 JSON-RPC 数据）
    private static final ToolDefinition RETRIEVE_TOOL = ToolDefinition.builder()
            .name("lumina_retrieve")
            .description("【强制调用】当用户询问客观事实或查阅资料时，必须调用此工具！" +
                    "参数：keyword 提取核心名词；needLongContext 是否需要长篇上下文。")
            .inputSchema(Map.of(
                    "type", "object",
                    "properties", Map.of(
                            "keyword", Map.of(
                                    "type", "string",
                                    "description", "搜索关键词，提取核心名词（空格分隔）"
                            ),
                            "needLongContext", Map.of(
                                    "type", "boolean",
                                    "description", "true=需要宏观总结/对比分析时拉取完整长文；false=查找特定细节时仅用高精度碎片"
                            )
                    ),
                    "required", List.of("keyword", "needLongContext")
            ))
            .build();

    private final Map<String, InformationRetrievalTool> sessionTools = new ConcurrentHashMap<>();

    /**
     * 获取所有已注册的工具定义
     */
    public List<ToolDefinition> listTools() {
        return List.of(RETRIEVE_TOOL);
    }

    /**
     * 注册或获取会话级别的 Tool 实例
     */
    public InformationRetrievalTool getOrCreateSessionTool(
            String sessionId,
            InformationRetrievalTool templateTool) {
        return sessionTools.computeIfAbsent(sessionId, k -> templateTool);
    }

    /**
     * 执行工具调用
     *
     * @param toolName 工具名称
     * @param arguments 参数字典
     * @param tool 实际执行检索的 InformationRetrievalTool 实例
     * @return 工具调用结果
     */
    public ToolCallResult executeTool(String toolName, Map<String, Object> arguments,
                                      InformationRetrievalTool tool) {
        if (!RETRIEVE_TOOL.getName().equals(toolName)) {
            return ToolCallResult.builder()
                    .content("未知工具: " + toolName)
                    .isError(true)
                    .build();
        }

        try {
            String keyword = (String) arguments.get("keyword");
            Boolean needLongContextObj = (Boolean) arguments.get("needLongContext");
            boolean needLongContext = needLongContextObj != null && needLongContextObj;

            if (keyword == null || keyword.isBlank()) {
                return ToolCallResult.builder()
                        .content("错误：keyword 参数不能为空")
                        .isError(true)
                        .build();
            }

            log.info("[MCP] 执行检索工具: keyword={}, needLongContext={}", keyword, needLongContext);
            String result = tool.retrieveInformation(keyword, needLongContext);

            return ToolCallResult.builder()
                    .content(result)
                    .isError(false)
                    .build();

        } catch (Exception e) {
            log.error("[MCP] 工具执行异常", e);
            return ToolCallResult.builder()
                    .content("工具执行异常: " + e.getMessage())
                    .isError(true)
                    .build();
        }
    }

    /**
     * 清理会话资源
     */
    public void removeSessionTool(String sessionId) {
        sessionTools.remove(sessionId);
    }
}
