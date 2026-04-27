package com.lumina.docs.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lumina.docs.entity.KbDocument;
import com.lumina.docs.mapper.KbDocumentMapper;
import com.lumina.rag.core.spi.DocumentIngestionEngine;
import com.lumina.rag.core.spi.VectorStoreService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.Date;

/**
 * 业务层知识库服务
 * 现在它薄得像一张纸，所有的脏活累活全被底层轮子 (DocumentIngestionEngine) 包揽了！
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeBaseService {

    private final DocumentIngestionEngine documentIngestionEngine;
    private final VectorStoreService vectorStoreService;
    private final StringRedisTemplate stringRedisTemplate;
    private final KafkaTemplate<String, String> kafkaTemplate;

    private final KbDocumentMapper kbDocumentMapper;

    private static final String TOPIC_DOC_UPDATE = "doc_update_topic";

    /**
     * 业务操作：纯文本上传入库
     */
    public String ingestText(String sourceName, String text, String indexName) {
        log.info("业务层收到文档 [{}], 准备委托给核心轮子进行黑盒摄入...", sourceName);

        // 核心轮子接管：自动存Redis、自动切块策略、自动打烙印、自动存ES！
        // 并且返回绝对安全的 parentId
        String parentId = documentIngestionEngine.ingest(sourceName, text, indexName);

        log.info("业务层摄入完成！拿到核心引擎返回的 ParentID: {}", parentId);
        return parentId;
    }

    /**
     * 接收真实的物理文件 -> 提取文本 -> 调轮子建索引 -> 存入 MySQL！
     */
    @Transactional(rollbackFor = Exception.class) // 开启数据库事务防线
    public String ingestFile(MultipartFile file, String indexName) {
        String fileName = file.getOriginalFilename();
        log.info("接收到物理文件上传: [{}], 大小: {} Bytes", fileName, file.getSize());

        // 1. 硬核解析：生啃 PDF 获取纯文本
        String extractedText = parsePdf(file);
        log.info("PDF 文本提取成功，共计 {} 个字符", extractedText.length());

        // ==========================================
        // 【架构级防御：空数据断路器】
        // 拦截纯图片版 PDF 或空白文件，防止底层引擎崩溃！
        // ==========================================
        if (extractedText == null || extractedText.trim().length() < 10) {
            log.error("拦截无效文件上传！可能为扫描版或纯图片 PDF。");
            throw new IllegalArgumentException("文件内容提取失败！请确保上传的 PDF 包含可选择的纯文本层，暂不支持纯图片或扫描件！");
        }

        // 2. 呼叫轮子：黑盒处理（自动切块、向量化、打烙印、存ES和Redis）
        String parentId = documentIngestionEngine.ingest(fileName, extractedText, indexName);

        // 3. 落盘 MySQL：留下真实的物理案底！
        KbDocument docRecord = KbDocument.builder()
                .docName(fileName)
                .esIndexName(indexName)
                .esParentId(parentId)
                .status(1)
                .createTime(new Date())
                .build();
        kbDocumentMapper.insert(docRecord);

        log.info("业务层摄入与落盘完美闭环！MySQL ID: {}, ES ParentID: {}", docRecord.getId(), parentId);
        return parentId;
    }

    /**
     * 业务操作：高危的数据更新与缓存炸毁
     */
    @Transactional(rollbackFor = Exception.class)
    public String updateDocument(String oldParentId, String newText, String indexName) {
        log.info("业务层开始执行文档更新与销毁流程，目标旧ID: {}", oldParentId);

        // 1. MySQL 逻辑删除 (打上死亡标记 0)
        KbDocument oldDoc = kbDocumentMapper.selectOne(
                new LambdaQueryWrapper<KbDocument>().eq(KbDocument::getEsParentId, oldParentId));
        if (oldDoc != null) {
            oldDoc.setStatus(0);
            kbDocumentMapper.updateById(oldDoc);
        }

        // 2. 底层物理大清洗 (ES 碎片与 Redis 父文档)
        documentIngestionEngine.removeDocument(indexName, oldParentId);

        // 3. 触发 Kafka 广播清理 AI 缓存
        log.info("业务层发送 Kafka 消息，通知全网清理 oldParentId: {} 的 AI 缓存", oldParentId);
        kafkaTemplate.send(TOPIC_DOC_UPDATE, oldParentId);

        // 4. 将新文本重新喂给摄入引擎，生成全新的 parentId 和 切块
        String newParentId = documentIngestionEngine.ingest(oldDoc != null ? oldDoc.getDocName() : "更新档案", newText, indexName);
        KbDocument newRecord = KbDocument.builder()
                .docName(oldDoc != null ? oldDoc.getDocName() : "更新档案")
                .esIndexName(indexName)
                .esParentId(newParentId)
                .status(1)
                .createTime(new Date())
                .build();
        kbDocumentMapper.insert(newRecord);

        log.info("业务层更新流程完毕！旧文档已销毁，新文档 ParentID: {}", newParentId);
        return newParentId;
    }

    /**
     * 内部工具库：解析 PDF 物理文件
     */
    private String parsePdf(MultipartFile file) {
        try (PDDocument document = PDDocument.load(file.getInputStream())) {
            PDFTextStripper stripper = new PDFTextStripper();
            return stripper.getText(document);
        } catch (Exception e) {
            log.error("PDF 解析惨遭失败！", e);
            throw new RuntimeException("PDF 文件解析异常，请检查文件格式！", e);
        }
    }
}