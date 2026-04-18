package com.lumina.docs.controller;

import com.lumina.docs.service.KnowledgeBaseService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/knowledge")
@RequiredArgsConstructor
public class KnowledgeController {

    private final KnowledgeBaseService knowledgeBaseService;

    @PostMapping("/upload-text")
    public String uploadText(
            @RequestParam String sourceName,
            @RequestBody String text,
            @RequestParam(defaultValue = "default_workspace") String indexName) {

        knowledgeBaseService.ingestText(sourceName, text, indexName);
        return "SUCCESS: 文档 [" + sourceName + "] 已成功灌入知识库 [" + indexName + "]！";
    }
}