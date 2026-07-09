package com.lumina.rag.core.langgraph.node;

import com.lumina.rag.core.langgraph.state.RagState;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * [RAG Workflow] Answer Generation Node
 *
 * Generates the final answer based on retrieved documents.
 * Documents are assembled into a readable format with source references.
 */
@Slf4j
public class GenerateNode implements Node<RagState> {

    @Override
    public RagState apply(RagState state) {
        String error = state.getError();
        if (error != null && !error.isEmpty()) {
            String answer = String.format(
                    "Sorry, I encountered an issue while searching the knowledge base: %s\n\n" +
                    "Suggestions:\n" +
                    "1. Check if the knowledge base contains relevant information\n" +
                    "2. Try different keywords\n" +
                    "3. Contact administrator if the issue persists",
                    error
            );
            state.setFinalAnswer(answer);
            log.info("returning error message to user");
            return state;
        }

        List<String> docs = state.getRetrievedDocs();
        String keyword = state.getKeyword();

        if (docs == null || docs.isEmpty()) {
            state.setFinalAnswer("Sorry, no information found for '" + keyword + "' in the knowledge base.");
            log.info("no results found, returning empty result message");
            return state;
        }

        StringBuilder answer = new StringBuilder();
        answer.append("Based on the knowledge base, here is the information about '")
                .append(keyword).append("':\n\n");

        for (int i = 0; i < docs.size(); i++) {
            String doc = docs.get(i);
            if (doc != null && !doc.isBlank()
                    && !doc.contains("system warning")
                    && !doc.contains("no results")) {
                answer.append("---\n");
                answer.append(doc.trim());
                answer.append("\n");
            }
        }

        answer.append("\n---\n");
        answer.append("> Information sourced from knowledge base.");

        state.setFinalAnswer(answer.toString());
        log.info("generated answer from {} document segments", docs.size());
        return state;
    }
}
