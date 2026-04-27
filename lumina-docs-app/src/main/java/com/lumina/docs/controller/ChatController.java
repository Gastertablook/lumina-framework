package com.lumina.docs.controller;

import com.lumina.rag.core.constant.LuminaConstants;
import com.lumina.rag.core.spi.LuminaRagClient;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {

    // 核心：只需注入我们的轮子网关！
    private final LuminaRagClient luminaRagClient;

    // 定义标准的请求 DTO，封装所有复杂参数
    @Data
    public static class ChatRequest {
        private String query;
        private String sessionId;
        private String indexName = LuminaConstants.DEFAULT_INDEX_NAME; // 默认值
        private Map<String, Object> metadataFilters; // 接受前端复杂的业务过滤条件
    }

    @PostMapping(value = "/stream", produces = "text/event-stream;charset=UTF-8")
    public SseEmitter chat(@RequestBody ChatRequest request) {
        
        String sessionId = request.getSessionId();
        if (sessionId == null || sessionId.trim().isEmpty()) {
            // 模拟当前会话ID和驾驭约束 (比如强制只在某个知识库里搜)
            sessionId = java.util.UUID.randomUUID().toString();
        }

        Map<String, Object> filters = request.getMetadataFilters() != null ? request.getMetadataFilters() : new HashMap<>();

        // 完美向下透传，不遗漏任何业务条件，呼叫底层的 V8 引擎！
        return luminaRagClient.chatStream(request.getQuery(), sessionId, request.getIndexName(), filters);
    }
}