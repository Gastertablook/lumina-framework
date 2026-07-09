package com.lumina.rag.core.langgraph.node;

import com.lumina.rag.core.langgraph.state.RagState;
import com.lumina.rag.core.tracing.LuminaTracer;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.function.BiFunction;

/**
 * [RAG Workflow] Knowledge Retrieval Node
 *
 * Calls the underlying retrieval tool to fetch relevant documents
 * from the knowledge base based on extracted keywords.
 *
 * This is an adapter node that wraps InformationRetrievalTool
 * into the LangGraph workflow via a BiFunction.
 */
@Slf4j
public class RetrieveNode implements Node<RagState> {

    private final BiFunction<String, Boolean, String> retriever;

    public RetrieveNode(BiFunction<String, Boolean, String> retriever) {
        this.retriever = retriever;
    }

    @Override
    public RagState apply(RagState state) {
        String keyword = state.getKeyword();
        boolean needLongContext = state.isNeedLongContext();

        if (keyword == null || keyword.isBlank()) {
            log.warn("keyword is empty, skipping retrieval");
            state.setError("keyword is empty");
            return state;
        }

        Span span = LuminaTracer.start("langgraph.retrieve")
                .setAttribute("keyword", keyword)
                .setAttribute("needLongContext", String.valueOf(needLongContext))
                .setAttribute("indexName", state.getIndexName());

        try (Scope scope = span.makeCurrent()) {
            log.info("retrieving: keyword={}, needLongContext={}", keyword, needLongContext);

            String result = retriever.apply(keyword, needLongContext);

            List<String> docs = List.of(result.split("\n---\n"));
            state.setRetrievedDocs(docs);

            if (result.contains("no results") || result.contains("warning")) {
                log.warn("no valid results found");
                state.setError("no documents found");
            }

            span.setAttribute("docCount", docs.size());
            span.setAttribute("resultLength", result.length());

            log.info("retrieval complete, got {} segments", docs.size());
            LuminaTracer.end(span);
            return state;

        } catch (Exception e) {
            log.error("retrieval exception", e);
            state.setError("retrieval error: " + e.getMessage());
            LuminaTracer.endWithError(span, e);
            return state;
        }
    }
}
