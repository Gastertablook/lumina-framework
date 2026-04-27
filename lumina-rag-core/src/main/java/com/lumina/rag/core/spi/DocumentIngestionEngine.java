package com.lumina.rag.core.spi;

/**
 * 【驾驭层】文档摄入引擎 SPI
 * 对外暴露极其简单的 API，内部强行接管安全性封装。
 */
public interface DocumentIngestionEngine {

    /**
     * 将长文本安全、合规地灌入底层系统
     * @return 返回生成的全局唯一父文档 ID (parentId)
     */
    String ingest(String sourceName, String text, String indexName);

    void deleteParentDoc(String oldParentId);

    void removeDocument(String indexName, String oldParentId);
}