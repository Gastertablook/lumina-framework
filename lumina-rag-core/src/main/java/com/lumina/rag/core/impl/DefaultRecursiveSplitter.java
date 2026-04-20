package com.lumina.rag.core.impl;

import com.lumina.rag.core.spi.DocumentSplitterStrategy;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.segment.TextSegment;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Component
public class DefaultRecursiveSplitter implements DocumentSplitterStrategy {
    @Override
    public List<String> split(String text) {
        // 默认的 500 字重叠切分算法
        DocumentSplitter splitter = DocumentSplitters.recursive(500, 50);
        return splitter.split(Document.from(text)).stream()
                .map(TextSegment::text)
                .collect(Collectors.toList());
    }
}