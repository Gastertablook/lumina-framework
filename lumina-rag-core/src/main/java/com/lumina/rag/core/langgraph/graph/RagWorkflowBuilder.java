package com.lumina.rag.core.langgraph.graph;

import com.lumina.rag.core.langgraph.edge.IntentRoutingEdge;
import com.lumina.rag.core.langgraph.node.*;
import com.lumina.rag.core.langgraph.state.RagState;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.function.BiFunction;

/**
 * [RAG Workflow] Workflow Builder
 *
 * Assembles the complete RAG workflow graph with the following topology:
 *
 *          +------------------+
 *          |  intent_classify |  <-- entry point
 *          +--------+---------+
 *                   |
 *          +--------+---------+
 *          |  conditional     |
 *          |  routing         |
 *          +--+----+----+-----+
 *             |    |    |
 *    RETRIEVE |    |    | GENERAL
 *             v    |    v
 *   +----------------+  +------------------+
 *   | keyword_extract |  | general_response |
 *   +--------+--------+  +--------+---------+
 *            |                     |
 *            v                     |
 *   +----------------+             |
 *   |    retrieve    |             |
 *   +--------+-------+             |
 *            |                     |
 *            v                     |
 *   +----------------+             |
 *   |    generate    |             |
 *   +--------+-------+             |
 *            |                     |
 *            +-------+-------------+
 *                    |
 *                    v
 *             +------+------+
 *             |     END     |
 *             +-------------+
 */
@Slf4j
public class RagWorkflowBuilder {

    // ============ Node name constants ============
    public static final String NODE_INTENT_CLASSIFY  = "intent_classify";
    public static final String NODE_KEYWORD_EXTRACT  = "keyword_extract";
    public static final String NODE_RETRIEVE         = "retrieve";
    public static final String NODE_GENERATE         = "generate";
    public static final String NODE_GENERAL_RESPONSE = "general_response";
    public static final String NODE_ERROR_HANDLER    = "error_handler";
    public static final String NODE_END              = "end";

    /**
     * Build a complete RAG workflow graph
     *
     * @param retriever retrieval function: (keyword, needLongContext) -> result text
     * @return compiled StateGraph
     */
    public static StateGraph<RagState> build(BiFunction<String, Boolean, String> retriever) {
        StateGraph<RagState> graph = new StateGraph<>();

        // ============ 1. Register nodes ============
        graph.addNode(NODE_INTENT_CLASSIFY, new IntentClassifierNode());
        graph.addNode(NODE_KEYWORD_EXTRACT, new KeywordExtractNode());
        graph.addNode(NODE_RETRIEVE, new RetrieveNode(retriever));
        graph.addNode(NODE_GENERATE, new GenerateNode());
        graph.addNode(NODE_GENERAL_RESPONSE, new GeneralResponseNode());
        graph.addNode(NODE_ERROR_HANDLER, state -> {
            String msg = state.getUserMessage();
            state.setFinalAnswer("I'm not sure I understand: '" + msg + "'.\n\n" +
                    "You can ask me things like:\n" +
                    "- 'search for budget report' - query knowledge base\n" +
                    "- 'hello' - greeting\n" +
                    "- 'what is our policy' - ask about specific information");
            return state;
        });

        // ============ 2. Set entry and finish points ============
        graph.setEntryPoint(NODE_INTENT_CLASSIFY);
        graph.setFinishPoint(NODE_GENERAL_RESPONSE);
        graph.setFinishPoint(NODE_GENERATE);
        graph.setFinishPoint(NODE_ERROR_HANDLER);

        // ============ 3. Add edges ============
        IntentRoutingEdge routingEdge = new IntentRoutingEdge();
        graph.addConditionalEdge(NODE_INTENT_CLASSIFY, routingEdge, Map.of(
                "RETRIEVE -> keyword_extract", "retrieve path",
                "GENERAL -> general_response", "general path",
                "UNKNOWN -> error_handler", "unknown path"
        ));

        graph.addEdge(NODE_KEYWORD_EXTRACT, NODE_RETRIEVE);
        graph.addEdge(NODE_RETRIEVE, NODE_GENERATE);

        // ============ 4. Compile ============
        graph.compile();

        log.info("RAG workflow graph built successfully");
        return graph;
    }
}
