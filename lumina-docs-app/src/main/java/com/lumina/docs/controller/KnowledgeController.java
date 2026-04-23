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

        // 拿到底层引擎生成的血缘 ID
        String parentId = knowledgeBaseService.ingestText(sourceName, text, indexName);

        return "SUCCESS: 文档 [" + sourceName + "] 已成功灌入知识库！它的全局唯一ID (ParentID) 是: " + parentId;
    }

    @PostMapping("/update-doc")
    public String updateDoc(
            @RequestParam String oldDocId,
            @RequestBody String newText,
            @RequestParam(defaultValue = "default_workspace") String indexName) {

        // 业务编排全部交由 Service 执行
        String newParentId = knowledgeBaseService.updateDocument(oldDocId, newText, indexName);
        return "SUCCESS: 旧文档已清理并炸毁缓存，新文档摄入成功！新 ParentID: " + newParentId;
    }
}