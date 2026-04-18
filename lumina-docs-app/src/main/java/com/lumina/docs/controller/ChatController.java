package com.lumina.docs.controller;

import com.lumina.rag.core.spi.LuminaRagClient;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {

    // 核心：只需注入我们的轮子网关！
    private final LuminaRagClient luminaRagClient;

    @GetMapping(value = "/stream", produces = "text/event-stream;charset=UTF-8")
    public SseEmitter chat(
            @RequestParam String query,
            @RequestParam(defaultValue = "default_workspace") String indexName) {

        // 模拟当前会话ID和驾驭约束 (比如强制只在某个知识库里搜)
        String sessionId = UUID.randomUUID().toString();
        Map<String, Object> metadataFilters = new HashMap<>();
        // constraints.put("userId", "1001"); // 未来做权限隔离的伏笔

        // 一行代码，呼叫底层的 V8 引擎！
        return luminaRagClient.chatStream(query, sessionId, indexName, metadataFilters);
    }
}