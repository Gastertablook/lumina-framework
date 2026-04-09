package com.lumina.rag.core.spi;

import com.lumina.rag.core.domain.DocumentChunk;
import java.util.List;

/**
 * 数据摄入管道 SPI。
 * 由具体的业务层 (lumina-docs-app) 去实现。
 * core 模块不关心你是读 PDF 还是读 MySQL。
 */
public interface DocumentProvider {

    /**
     * 校验阀门：判断当前 Provider 能否处理该来源 (例如 sourceUri 为 "file://xxx.pdf" 或 "mysql://table")
     */
    boolean supports(String sourceUri);

    /**
     * 核心：将任意来源的数据，解析、切块并转换为标准 DocumentChunk 列表
     */
    List<DocumentChunk> loadAndSplit(String sourceUri);
}