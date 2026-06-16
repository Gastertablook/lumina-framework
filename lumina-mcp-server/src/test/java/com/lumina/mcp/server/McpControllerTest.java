package com.lumina.mcp.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lumina.mcp.server.controller.McpController;
import com.lumina.mcp.server.protocol.JsonRpcMessage;
import com.lumina.mcp.server.tool.LuminaMcpToolRegistry;
import com.lumina.rag.core.cache.SemanticCacheManager;
import com.lumina.rag.core.concurrent.RequestDeduplicator;
import com.lumina.rag.core.spi.VectorStoreService;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * MCP Controller 单元测试
 */
@WebMvcTest(McpController.class)
class McpControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    // ==================== McpController + RAG Core 依赖 ====================
    @MockBean
    private VectorStoreService vectorStoreService;
    @MockBean
    private StreamingChatLanguageModel streamingChatLanguageModel;
    @MockBean
    private EmbeddingModel embeddingModel;
    @MockBean
    private StringRedisTemplate stringRedisTemplate;
    @MockBean
    private SemanticCacheManager cacheManager;
    @MockBean
    private RequestDeduplicator deduplicator;
    @MockBean
    private LuminaMcpToolRegistry toolRegistry;

    @Test
    @DisplayName("server/info 应返回服务器信息")
    void serverInfo_ShouldReturnInfo() throws Exception {
        String request = objectMapper.writeValueAsString(
                new JsonRpcMessage.Request("1", "server/info", null)
        );

        mockMvc.perform(post("/mcp/message")
                        .param("sessionId", "test-session")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("1"))
                .andExpect(jsonPath("$.result.name").value("Lumina RAG MCP Server"));
    }

    @Test
    @DisplayName("tools/list 应返回工具列表")
    void toolsList_ShouldReturnTools() throws Exception {
        String request = objectMapper.writeValueAsString(
                new JsonRpcMessage.Request("2", "tools/list", null)
        );

        mockMvc.perform(post("/mcp/message")
                        .param("sessionId", "test-session")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("2"))
                .andExpect(jsonPath("$.result.tools").isArray());
    }

    @Test
    @DisplayName("未知方法应返回错误")
    void unknownMethod_ShouldReturnError() throws Exception {
        String request = objectMapper.writeValueAsString(
                new JsonRpcMessage.Request("3", "unknown/method", null)
        );

        mockMvc.perform(post("/mcp/message")
                        .param("sessionId", "test-session")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.error").exists())
                .andExpect(jsonPath("$.error.code").value(-32601));
    }

    @Test
    @DisplayName("无效 JSON 应返回解析错误")
    void invalidJson_ShouldReturnParseError() throws Exception {
        mockMvc.perform(post("/mcp/message")
                        .param("sessionId", "test-session")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("这不是 JSON"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    @DisplayName("SSE 端点应返回事件流")
    void sseEndpoint_ShouldReturnEventStream() throws Exception {
        mockMvc.perform(get("/mcp/sse")
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isOk());
    }
}
