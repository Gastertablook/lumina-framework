package com.lumina.rag.core.langgraph.node;

import com.lumina.rag.core.langgraph.state.RagState;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * [RAG Workflow] Intent Classifier Node
 *
 * Classifies user input into RETRIEVE, GENERAL, or UNKNOWN based on keywords.
 * This is a rule-based classifier; an LLM-based classifier can replace it later.
 */
@Slf4j
public class IntentClassifierNode implements Node<RagState> {

    private static final List<String> GREETING_KEYWORDS = List.of(
            "hello", "hi", "hey", "good morning", "good afternoon",
            "good evening", "goodbye", "bye", "thanks", "thank you",
            "who are you", "what can you do"
    );

    private static final List<String> RETRIEVE_KEYWORDS = List.of(
            "search", "find", "look up", "retrieve", "query",
            "document", "record", "knowledge", "information"
    );

    private static final List<String> QUESTION_WORDS = List.of(
            "what", "why", "how", "which", "where", "when"
    );

    @Override
    public RagState apply(RagState state) {
        String userMessage = state.getUserMessage();
        if (userMessage == null || userMessage.isBlank()) {
            log.warn("empty message, marking as UNKNOWN");
            state.setIntent(RagState.Intent.UNKNOWN);
            return state;
        }

        String msg = userMessage.trim().toLowerCase();

        // 1. Check greetings
        if (isGreeting(msg)) {
            log.info("classified as GENERAL (greeting): {}", userMessage);
            state.setIntent(RagState.Intent.GENERAL);
            return state;
        }

        // 2. Check retrieve keywords
        if (needsRetrieval(msg)) {
            log.info("classified as RETRIEVE: {}", userMessage);
            state.setIntent(RagState.Intent.RETRIEVE);
            return state;
        }

        // 3. Check question words
        if (isQuestion(msg)) {
            log.info("classified as RETRIEVE (question): {}", userMessage);
            state.setIntent(RagState.Intent.RETRIEVE);
            return state;
        }

        // 4. Default: UNKNOWN
        log.info("classified as UNKNOWN: {}", userMessage);
        state.setIntent(RagState.Intent.UNKNOWN);
        return state;
    }

    private boolean isGreeting(String msg) {
        return GREETING_KEYWORDS.stream().anyMatch(msg::contains);
    }

    private boolean needsRetrieval(String msg) {
        return RETRIEVE_KEYWORDS.stream().anyMatch(msg::contains);
    }

    private boolean isQuestion(String msg) {
        return QUESTION_WORDS.stream().anyMatch(msg::contains);
    }
}
