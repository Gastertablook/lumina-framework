package com.lumina.mcp.server.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lumina.mcp.server.protocol.JsonRpcMessage;
import com.lumina.mcp.server.protocol.McpConstants;
import com.lumina.mcp.server.tool.LuminaMcpToolRegistry;
import com.lumina.rag.core.agent.InformationRetrievalTool;
import com.lumina.rag.core.cache.SemanticCacheManager;
import com.lumina.rag.core.concurrent.RequestDeduplicator;
import com.lumina.rag.core.spi.VectorStoreService;
import dev.langchain4j.model.embedding.EmbeddingModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import javax.annotation.PreDestroy;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MCP 协议控制器
 *
 * 实现 MCP 的 HTTP 传输层（SSE + POST），使任何 MCP 客户端（Claude Desktop、Claude Code 等）
 * 都能发现并调用 Lumina RAG 引擎的知识库检索能力。
 *
 * 端点设计：
 * - GET  /mcp/sse    → SSE 长连接，推送事件
 * - POST /mcp/message → 接收客户端 JSON-RPC 消息
 */
@Slf4j
@RestController
@RequestMapping("/mcp")
@RequiredArgsConstructor
public class McpController {

    private final VectorStoreService vectorStoreService;
    private final EmbeddingModel embeddingModel;
    private final StringRedisTemplate stringRedisTemplate;
    private final SemanticCacheManager cacheManager;
    private final RequestDeduplicator deduplicator;
    private final LuminaMcpToolRegistry toolRegistry;
    private final ObjectMapper objectMapper;

    // 默认租户配置（可通过配置文件覆盖）
    private String defaultIndexName = "default_workspace";

    // 管理所有 SSE 连接
    private final Map<String, SseEmitter> sseEmitters = new ConcurrentHashMap<>();

    // 管理每个会话的 Tool 实例
    private final Map<String, InformationRetrievalTool> sessionTools = new ConcurrentHashMap<>();

    /**
     * SSE 端点：客户端通过此端点建立长连接
     * MCP 协议要求先建立 SSE 连接，然后通过 POST /mcp/message 发送请求
     */
    @GetMapping(value = "/sse", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter handleSse() {
        String sessionId = java.util.UUID.randomUUID().toString();
        SseEmitter emitter = new SseEmitter(0L); // 0 = 不超时

        sseEmitters.put(sessionId, emitter);

        // 发送 endpoint 事件，告诉客户端 POST 地址
        try {
            emitter.send(SseEmitter.event()
                    .name(McpConstants.SSE_EVENT_ENDPOINT)
                    .data("/mcp/message?sessionId=" + sessionId));
        } catch (IOException e) {
            log.error("[MCP] SSE 发送 endpoint 事件失败", e);
            sseEmitters.remove(sessionId);
            return null;
        }

        // 客户端断开时清理资源
        emitter.onCompletion(() -> {
            log.info("[MCP] SSE 连接关闭，清理会话: {}", sessionId);
            cleanupSession(sessionId);
        });
        emitter.onTimeout(() -> {
            log.info("[MCP] SSE 连接超时，清理会话: {}", sessionId);
            cleanupSession(sessionId);
        });
        emitter.onError(e -> {
            log.error("[MCP] SSE 连接异常", e);
            cleanupSession(sessionId);
        });

        log.info("[MCP] 新的 SSE 连接建立, sessionId={}", sessionId);
        return emitter;
    }

    /**
     * 消息端点：接收客户端的 JSON-RPC 请求
     */
    @PostMapping(value = "/message", produces = MediaType.APPLICATION_JSON_VALUE)
    public JsonRpcMessage.Response handleMessage(
            @RequestParam("sessionId") String sessionId,
            @RequestBody String requestBody) {

        JsonRpcMessage.Request request;
        try {
            request = objectMapper.readValue(requestBody, JsonRpcMessage.Request.class);
        } catch (Exception e) {
            log.error("[MCP] JSON-RPC 解析失败", e);
            return JsonRpcMessage.Response.error(null, McpConstants.ERROR_PARSE_ERROR, "JSON 解析失败");
        }

        String id = request.getId();
        String method = request.getMethod();

        log.info("[MCP] 收到请求: method={}, id={}", method, id);

        try {
            switch (method) {
                case McpConstants.METHOD_SERVER_INFO:
                    return handleServerInfo(id);

                case McpConstants.METHOD_TOOLS_LIST:
                    return handleToolsList(id);

                case McpConstants.METHOD_TOOLS_CALL:
                    return handleToolCall(id, request.getParams(), sessionId);

                default:
                    return JsonRpcMessage.Response.error(id,
                            McpConstants.ERROR_METHOD_NOT_FOUND, "未知方法: " + method);
            }
        } catch (Exception e) {
            log.error("[MCP] 处理请求异常", e);
            return JsonRpcMessage.Response.error(id,
                    McpConstants.ERROR_INTERNAL, "服务器内部错误: " + e.getMessage());
        }
    }

    /**
     * 处理 server/info：返回服务器信息
     */
    private JsonRpcMessage.Response handleServerInfo(String id) {
        Map<String, Object> info = Map.of(
                "name", "Lumina RAG MCP Server",
                "version", "1.0.0",
                "description", "Lumina RAG 框架的 MCP 标准化接口，提供知识库检索能力"
        );
        return new JsonRpcMessage.Response(id, info);
    }

    /**
     * 处理 tools/list：返回可用工具列表
     */
    private JsonRpcMessage.Response handleToolsList(String id) {
        List<JsonRpcMessage.ToolDefinition> tools = toolRegistry.listTools();
        return new JsonRpcMessage.Response(id, Map.of("tools", tools));
    }

    /**
     * 处理 tools/call：执行工具调用
     */
    private JsonRpcMessage.Response handleToolCall(String id, Object params, String sessionId) {
        Map<String, Object> paramsMap;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) params;
            paramsMap = map;
        } catch (ClassCastException e) {
            return JsonRpcMessage.Response.error(id,
                    McpConstants.ERROR_INVALID_PARAMS, "参数格式错误");
        }

        String toolName = (String) paramsMap.get("name");
        @SuppressWarnings("unchecked")
        Map<String, Object> arguments = (Map<String, Object>) paramsMap.get("arguments");

        if (toolName == null) {
            return JsonRpcMessage.Response.error(id,
                    McpConstants.ERROR_INVALID_PARAMS, "缺少工具名称");
        }

        if (arguments == null) {
            arguments = Map.of();
        }

        // 获取或创建会话级别的 Tool 实例
        InformationRetrievalTool tool = getOrCreateTool(sessionId);
        JsonRpcMessage.ToolCallResult result = toolRegistry.executeTool(toolName, arguments, tool);

        return new JsonRpcMessage.Response(id, Map.of(
                "content", List.of(Map.of(
                        "type", "text",
                        "text", result.getContent()
                )),
                "isError", result.isError()
        ));
    }

    /**
     * 获取或创建会话级别的 InformationRetrievalTool 实例
     * 每个会话有自己的 refDocIds 列表，保证隔离
     */
    private InformationRetrievalTool getOrCreateTool(String sessionId) {
        return sessionTools.computeIfAbsent(sessionId, key -> {
            List<String> refDocIds = new ArrayList<>();
            return new InformationRetrievalTool(
                    vectorStoreService, embeddingModel, stringRedisTemplate,
                    cacheManager, deduplicator,
                    defaultIndexName, null, refDocIds
            );
        });
    }

    /**
     * 清理会话资源
     */
    private void cleanupSession(String sessionId) {
        sessionTools.remove(sessionId);
        toolRegistry.removeSessionTool(sessionId);
        sseEmitters.remove(sessionId);
    }

    @PreDestroy
    public void destroy() {
        log.info("[MCP] 服务器关闭，清理所有会话");
        sessionTools.clear();
        sseEmitters.clear();
    }
}
