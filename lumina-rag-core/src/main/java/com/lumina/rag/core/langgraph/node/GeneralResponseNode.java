package com.lumina.rag.core.langgraph.node;

import com.lumina.rag.core.langgraph.state.RagState;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

/**
 * [RAG Workflow] General Response Node
 *
 * Handles conversations that don't require knowledge base retrieval (greetings, etc.)
 * Uses template-based responses; can be upgraded to LLM-driven responses later.
 */
@Slf4j
public class GeneralResponseNode implements Node<RagState> {

    private static final Map<String, String> GREETING_RESPONSES = Map.of(
            "hello", "Hello! I'm Lumina RAG Assistant. How can I help you?",
            "hi", "Hi there! I'm Lumina RAG Assistant. How can I help you?",
            "hey", "Hey! I'm Lumina RAG Assistant. What can I do for you?",
            "thanks", "You're welcome! Feel free to ask if you have more questions.",
            "thank you", "You're welcome! Happy to help."
    );

    @Override
    public RagState apply(RagState state) {
        String userMessage = state.getUserMessage();
        String response = generateResponse(userMessage);

        state.setFinalAnswer(response);
        log.info("generated general response: {}", response);
        return state;
    }

    private String generateResponse(String message) {
        if (message == null) return "";

        String lowerMsg = message.trim().toLowerCase();

        for (Map.Entry<String, String> entry : GREETING_RESPONSES.entrySet()) {
            if (lowerMsg.contains(entry.getKey())) {
                return entry.getValue();
            }
        }

        return "Hello! I'm Lumina RAG Assistant. I can help you search the knowledge base or answer questions about your documents.";
    }
}
