package com.lumina.docs.controller;

import com.lumina.docs.service.KnowledgeBaseService;
import com.lumina.rag.core.constant.LuminaConstants;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/knowledge")
@RequiredArgsConstructor
public class KnowledgeController {

    private final KnowledgeBaseService knowledgeBaseService;

    private final com.lumina.docs.mapper.KbDocumentMapper kbDocumentMapper;

    // 接受前端 Multipart 物理文件上传的接口
    @PostMapping("/upload-file")
    public String uploadFile(
            @RequestParam("file") MultipartFile file,
            @RequestParam(defaultValue = LuminaConstants.DEFAULT_INDEX_NAME) String indexName) {

        String parentId = knowledgeBaseService.ingestFile(file, indexName);
        return "SUCCESS: 物理文件 [" + file.getOriginalFilename() + "] 已解析并落盘 MySQL！全局唯一ID: " + parentId;
    }

    @PostMapping("/upload-text")
    public String uploadText(
            @RequestParam String sourceName,
            @RequestBody String text,
            @RequestParam(defaultValue = LuminaConstants.DEFAULT_INDEX_NAME) String indexName) {

        // 拿到底层引擎生成的血缘 ID
        String parentId = knowledgeBaseService.ingestText(sourceName, text, indexName);

        return "SUCCESS: 文档 [" + sourceName + "] 已成功灌入知识库！它的全局唯一ID (ParentID) 是: " + parentId;
    }

    @PostMapping("/update-doc")
    public String updateDoc(
            @RequestParam String oldDocId,
            @RequestBody String newText,
            @RequestParam(defaultValue = LuminaConstants.DEFAULT_INDEX_NAME) String indexName) {

        // 业务编排全部交由 Service 执行
        String newParentId = knowledgeBaseService.updateDocument(oldDocId, newText, indexName);
        return "SUCCESS: 旧文档已清理并炸毁缓存，新文档摄入成功！新 ParentID: " + newParentId;
    }

    @GetMapping("/list")
    public Object listDocuments(@RequestParam(defaultValue = "lumina_pdf_kb") String indexName) {
        // 利用 MyBatis-Plus 一行代码查出该知识库下的所有未删除文档！
        return kbDocumentMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.lumina.docs.entity.KbDocument>()
                        .eq(com.lumina.docs.entity.KbDocument::getEsIndexName, indexName)
                        .eq(com.lumina.docs.entity.KbDocument::getStatus, 1)
                        .orderByDesc(com.lumina.docs.entity.KbDocument::getCreateTime)
        );
    }
}