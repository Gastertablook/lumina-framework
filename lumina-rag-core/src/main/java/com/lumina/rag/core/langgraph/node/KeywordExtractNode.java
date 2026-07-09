package com.lumina.rag.core.langgraph.node;

import com.lumina.rag.core.langgraph.state.RagState;
import lombok.extern.slf4j.Slf4j;

import java.util.Set;

/**
 * [RAG Workflow] Keyword Extraction Node
 *
 * Extracts search keywords from user input using rule-based approach:
 * 1. Extract quoted content (single/double/angle quotes)
 * 2. Filter out stop words
 * 3. Fallback: return original input
 */
@Slf4j
public class KeywordExtractNode implements Node<RagState> {

    /** English stop words */
    private static final Set<String> STOP_WORDS = Set.of(
            "the", "a", "an", "is", "are", "was", "were", "be", "been",
            "being", "have", "has", "had", "do", "does", "did", "will",
            "would", "could", "should", "may", "might", "can", "shall",
            "to", "of", "in", "for", "on", "with", "at", "by", "from",
            "as", "into", "through", "during", "before", "after", "above",
            "below", "between", "out", "off", "over", "under", "again",
            "further", "then", "once", "here", "there", "when", "where",
            "why", "how", "all", "each", "every", "both", "few", "more",
            "most", "other", "some", "such", "no", "nor", "not", "only",
            "own", "same", "so", "than", "too", "very", "just", "because",
            "and", "but", "or", "if", "while", "that", "this", "these",
            "those", "it", "its", "i", "me", "my", "we", "our", "you",
            "your", "he", "him", "his", "she", "her", "they", "them",
            "their", "what", "which", "who", "whom", "about", "up"
    );

    @Override
    public RagState apply(RagState state) {
        String userMessage = state.getUserMessage();
        if (userMessage == null || userMessage.isBlank()) {
            state.setKeyword("");
            return state;
        }

        String keyword = extractKeyword(userMessage);
        state.setKeyword(keyword);
        log.info("extracted keyword: '{}' -> '{}'", userMessage, keyword);
        return state;
    }

    private String extractKeyword(String text) {
        // 1. Try extracting quoted content
        String quoted = extractQuotedContent(text);
        if (quoted != null) {
            return quoted;
        }

        // 2. Split and filter stop words
        String[] words = text.split("[\\s,;:.!?()\\[\\]{}]+");
        StringBuilder sb = new StringBuilder();
        for (String word : words) {
            String trimmed = word.trim().toLowerCase();
            if (!trimmed.isEmpty() && !STOP_WORDS.contains(trimmed)) {
                if (sb.length() > 0) sb.append(" ");
                sb.append(word.trim());
            }
        }

        if (sb.length() > 0) {
            return sb.toString();
        }

        // 3. Fallback: return original
        return text;
    }

    private String extractQuotedContent(String text) {
        // Angle brackets: << >>
        int idx1 = text.indexOf('<');
        int idx2 = text.indexOf('>');
        if (idx1 >= 0 && idx2 > idx1) {
            return text.substring(idx1 + 1, idx2).trim();
        }

        // Double quotes
        idx1 = text.indexOf('"');
        idx2 = text.indexOf('"', idx1 + 1);
        if (idx1 >= 0 && idx2 > idx1) {
            return text.substring(idx1 + 1, idx2).trim();
        }

        // Single quotes
        idx1 = text.indexOf('\'');
        idx2 = text.indexOf('\'', idx1 + 1);
        if (idx1 >= 0 && idx2 > idx1) {
            return text.substring(idx1 + 1, idx2).trim();
        }

        return null;
    }
}
