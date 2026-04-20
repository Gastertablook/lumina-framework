package com.lumina.rag.core.spi;

import java.util.List;

/**
 * 【驾驭层】文档切块策略 SPI
 * 轮子只定义接口，具体的切块规则交由应用层或默认实现来决定！
 */
public interface DocumentSplitterStrategy {
    // 传入长文本，返回切分好的纯文本块集合
    List<String> split(String text);
}