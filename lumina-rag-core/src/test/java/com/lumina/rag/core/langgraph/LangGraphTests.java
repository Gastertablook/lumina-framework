package com.lumina.rag.core.langgraph;

import com.lumina.rag.core.langgraph.edge.IntentRoutingEdge;
import com.lumina.rag.core.langgraph.graph.RagWorkflowBuilder;
import com.lumina.rag.core.langgraph.graph.StateGraph;
import com.lumina.rag.core.langgraph.node.*;
import com.lumina.rag.core.langgraph.state.AgentState;
import com.lumina.rag.core.langgraph.state.RagState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LangGraph workflow engine comprehensive tests.
 * Covers: StateGraph engine, nodes, full RAG workflow, conditional routing, edge cases.
 */
class LangGraphTests {

    // ============ 1. StateGraph Core Engine Tests ============

    @Nested
    @DisplayName("StateGraph core engine")
    class StateGraphEngineTests {

        @Test
        @DisplayName("should execute a simple linear graph")
        void shouldExecuteLinearGraph() {
            StateGraph<AgentState> graph = new StateGraph<>();
            AtomicInteger counter = new AtomicInteger(0);

            graph.addNode("start", state -> {
                counter.incrementAndGet();
                state.set("step", "start");
                return state;
            });
            graph.addNode("end", state -> {
                counter.incrementAndGet();
                state.set("step", "end");
                return state;
            });
            graph.setEntryPoint("start");
            graph.setFinishPoint("end");
            graph.addEdge("start", "end");
            graph.compile();

            AgentState result = graph.invoke(new AgentState());

            assertEquals("end", result.get("step"));
            assertEquals(2, counter.get());
        }

        @Test
        @DisplayName("should throw when not compiled")
        void shouldThrowWhenNotCompiled() {
            StateGraph<AgentState> graph = new StateGraph<>();
            graph.addNode("start", state -> state);
            graph.setEntryPoint("start");

            assertThrows(IllegalStateException.class, () -> graph.invoke(new AgentState()));
        }

        @Test
        @DisplayName("should throw when no entry point set")
        void shouldThrowWhenNoEntryPoint() {
            StateGraph<AgentState> graph = new StateGraph<>();
            assertThrows(IllegalStateException.class, graph::compile);
        }

        @Test
        @DisplayName("should throw when entry point not found")
        void shouldThrowWhenEntryNotFound() {
            StateGraph<AgentState> graph = new StateGraph<>();
            graph.setEntryPoint("nonexistent");
            assertThrows(IllegalStateException.class, graph::compile);
        }

        @Test
        @DisplayName("should throw on duplicate node")
        void shouldThrowOnDuplicateNode() {
            StateGraph<AgentState> graph = new StateGraph<>();
            graph.addNode("a", state -> state);
            assertThrows(IllegalArgumentException.class,
                    () -> graph.addNode("a", state -> state));
        }

        @Test
        @DisplayName("should handle conditional edges")
        void shouldHandleConditionalEdges() {
            StateGraph<AgentState> graph = new StateGraph<>();

            graph.addNode("classify", state -> {
                String input = state.get("input");
                state.set("category", input.contains("retrieve") ? "retrieve" : "general");
                return state;
            });
            graph.addNode("retrieve", state -> {
                state.set("result", "retrieved data");
                return state;
            });
            graph.addNode("general", state -> {
                state.set("result", "general reply");
                return state;
            });

            graph.setEntryPoint("classify");
            graph.setFinishPoint("retrieve");
            graph.setFinishPoint("general");

            graph.addConditionalEdge("classify",
                    state -> "retrieve".equals(state.get("category")) ? "retrieve" : "general",
                    Map.of("retrieve", "retrieve path", "general", "general path"));

            graph.compile();

            AgentState state1 = new AgentState(Map.of("input", "please retrieve documents"));
            state1 = graph.invoke(state1);
            assertEquals("retrieved data", state1.get("result"));

            AgentState state2 = new AgentState(Map.of("input", "hello"));
            state2 = graph.invoke(state2);
            assertEquals("general reply", state2.get("result"));
        }

        @Test
        @DisplayName("should prevent infinite loops")
        void shouldPreventInfiniteLoop() {
            StateGraph<AgentState> graph = new StateGraph<>();

            graph.addNode("loop", state -> state);
            graph.setEntryPoint("loop");
            graph.addEdge("loop", "loop");
            graph.compile();

            assertThrows(IllegalStateException.class, () -> graph.invoke(new AgentState()));
        }

        @Test
        @DisplayName("should pass state between nodes")
        void shouldPassStateBetweenNodes() {
            StateGraph<AgentState> graph = new StateGraph<>();

            graph.addNode("add", state -> {
                int val = state.get("value");
                state.set("value", val + 1);
                return state;
            });
            graph.addNode("double", state -> {
                int val = state.get("value");
                state.set("value", val * 2);
                return state;
            });

            graph.setEntryPoint("add");
            graph.addEdge("add", "double");
            graph.compile();

            AgentState result = graph.invoke(new AgentState(Map.of("value", 5)));
            assertEquals(12, (int) result.get("value"));
        }

        @Test
        @DisplayName("should stop at finish point immediately after execution")
        void shouldStopAtFinishPointImmediately() {
            StateGraph<AgentState> graph = new StateGraph<>();

            AtomicInteger counter = new AtomicInteger(0);
            graph.addNode("first", state -> {
                counter.incrementAndGet();
                state.set("visited", "first");
                return state;
            });
            graph.addNode("second", state -> {
                counter.incrementAndGet();
                state.set("visited", "second");
                return state;
            });

            graph.setEntryPoint("first");
            graph.setFinishPoint("first");  // first 是 finish point
            graph.addEdge("first", "second");
            graph.compile();

            AgentState result = graph.invoke(new AgentState());
            // first 执行后立即停止，不会到 second
            assertEquals("first", result.get("visited"));
            assertEquals(1, counter.get());
        }

        @Test
        @DisplayName("conditional edge with unmapped route should still work")
        void conditionalEdgeWithUnmappedRoute() {
            StateGraph<AgentState> graph = new StateGraph<>();

            graph.addNode("router", state -> {
                state.set("route", "unknown_path");
                return state;
            });
            graph.addNode("fallback", state -> {
                state.set("handled", true);
                return state;
            });

            graph.setEntryPoint("router");
            graph.setFinishPoint("fallback");
            // 条件边返回 "unknown_path"，但映射中不存在这个节点也没关系
            // — 只要 evaluate() 返回的字符串是已注册的节点名即可
            graph.addConditionalEdge("router",
                    state -> "fallback",
                    Map.of("fallback", "fallback"));
            graph.compile();

            AgentState result = graph.invoke(new AgentState());
            assertTrue((Boolean) result.get("handled"));
        }

        @Test
        @DisplayName("should throw when conditional edge evaluates to unregistered node")
        void shouldThrowOnInvalidConditionalTarget() {
            StateGraph<AgentState> graph = new StateGraph<>();

            graph.addNode("router", state -> state);
            graph.addNode("valid", state -> state);

            graph.setEntryPoint("router");
            graph.setFinishPoint("valid");
            graph.addConditionalEdge("router",
                    state -> "nonexistent_node",  // 不存在的节点
                    Map.of("nonexistent_node", "bad"));
            graph.compile();

            assertThrows(IllegalStateException.class,
                    () -> graph.invoke(new AgentState()));
        }

        @Test
        @DisplayName("should throw when edge source is not registered")
        void shouldThrowOnInvalidEdgeSource() {
            StateGraph<AgentState> graph = new StateGraph<>();
            graph.addNode("a", state -> state);
            graph.setEntryPoint("a");
            // 边的源节点不存在
            graph.addEdge("nonexistent", "a");
            assertThrows(IllegalStateException.class, graph::compile);
        }

        @Test
        @DisplayName("should throw when conditional edge source is not registered")
        void shouldThrowOnInvalidConditionalEdgeSource() {
            StateGraph<AgentState> graph = new StateGraph<>();
            graph.addNode("a", state -> state);
            graph.setEntryPoint("a");
            graph.addConditionalEdge("nonexistent", state -> "a", Map.of("a", "a"));
            assertThrows(IllegalStateException.class, graph::compile);
        }

        @Test
        @DisplayName("should support chained API calls")
        void shouldSupportChainedApiCalls() {
            StateGraph<AgentState> graph = new StateGraph<>();

            graph.addNode("a", state -> { state.set("a", true); return state; })
                 .addNode("b", state -> { state.set("b", true); return state; })
                 .setEntryPoint("a")
                 .setFinishPoint("b")
                 .addEdge("a", "b")
                 .compile();

            AgentState result = graph.invoke(new AgentState());
            assertTrue((Boolean) result.get("a"));
            assertTrue((Boolean) result.get("b"));
        }

        @Test
        @DisplayName("getNodeNames should return all registered nodes")
        void getNodeNamesShouldReturnAllNodes() {
            StateGraph<AgentState> graph = new StateGraph<>();
            graph.addNode("a", state -> state);
            graph.addNode("b", state -> state);
            graph.addNode("c", state -> state);
            graph.setEntryPoint("a");

            assertEquals(3, graph.getNodeNames().size());
            assertTrue(graph.getNodeNames().containsAll(Set.of("a", "b", "c")));
        }
    }

    // ============ 2. Node Logic Tests ============

    @Nested
    @DisplayName("Intent Classifier Node")
    class IntentClassifierNodeTests {

        private IntentClassifierNode classifier;

        @BeforeEach
        void setUp() {
            classifier = new IntentClassifierNode();
        }

        @Test
        @DisplayName("greetings should be GENERAL")
        void greetingShouldBeGeneral() {
            assertIntent("hello", RagState.Intent.GENERAL);
            assertIntent("hi", RagState.Intent.GENERAL);
            assertIntent("hello world", RagState.Intent.GENERAL);
        }

        @Test
        @DisplayName("retrieve keywords should be RETRIEVE")
        void retrieveKeywordsShouldBeRetrieve() {
            assertIntent("search for budget", RagState.Intent.RETRIEVE);
            assertIntent("find documents", RagState.Intent.RETRIEVE);
            assertIntent("look up data", RagState.Intent.RETRIEVE);
            assertIntent("query information", RagState.Intent.RETRIEVE);
        }

        @Test
        @DisplayName("retrieve keywords should be RETRIEVE")
        void retrieveKeywordsShouldBeRetrieve2() {
            assertIntent("search document", RagState.Intent.RETRIEVE);
            assertIntent("find knowledge", RagState.Intent.RETRIEVE);
            assertIntent("look up record", RagState.Intent.RETRIEVE);
        }

        @Test
        @DisplayName("empty message should be UNKNOWN")
        void emptyMessageShouldBeUnknown() {
            assertIntent("", RagState.Intent.UNKNOWN);
            assertIntent("   ", RagState.Intent.UNKNOWN);
        }

        @Test
        @DisplayName("plain text without keywords should be UNKNOWN")
        void plainTextShouldBeUnknown() {
            assertIntent("weather is nice today", RagState.Intent.UNKNOWN);
            assertIntent("12345", RagState.Intent.UNKNOWN);
        }

        @Test
        @DisplayName("question words should trigger RETRIEVE")
        void questionWordsShouldBeRetrieve() {
            assertIntent("what is the budget for Q3", RagState.Intent.RETRIEVE);
            assertIntent("why did the project fail", RagState.Intent.RETRIEVE);
            assertIntent("how to deploy the application", RagState.Intent.RETRIEVE);
            assertIntent("which document describes the policy", RagState.Intent.RETRIEVE);
            assertIntent("where is the configuration file", RagState.Intent.RETRIEVE);
            assertIntent("when was the last deployment", RagState.Intent.RETRIEVE);
        }

        @Test
        @DisplayName("greeting + question word: greeting should win")
        void greetingOverridesQuestionWord() {
            // "who are you" 既匹配 greeting 又匹配 question word
            // greeting 检查在前，应返回 GENERAL
            assertIntent("who are you", RagState.Intent.GENERAL);
            assertIntent("what can you do", RagState.Intent.GENERAL);
        }

        @Test
        @DisplayName("null message should be UNKNOWN")
        void nullMessageShouldBeUnknown() {
            RagState state = new RagState();
            state.setUserMessage(null);
            state = classifier.apply(state);
            assertEquals(RagState.Intent.UNKNOWN, state.getIntent());
        }

        private void assertIntent(String message, RagState.Intent expected) {
            RagState state = new RagState();
            state.setUserMessage(message);
            state = classifier.apply(state);
            assertEquals(expected, state.getIntent(),
                    "message '" + message + "' should be " + expected);
        }
    }

    @Nested
    @DisplayName("Keyword Extract Node")
    class KeywordExtractNodeTests {

        private KeywordExtractNode extractor;

        @BeforeEach
        void setUp() {
            extractor = new KeywordExtractNode();
        }

        @Test
        @DisplayName("should extract single-quoted content")
        void shouldExtractSingleQuotedContent() {
            assertKeyword("find 'budget report' info", "budget report");
        }

        @Test
        @DisplayName("should extract double-quoted content")
        void shouldExtractDoubleQuotedContent() {
            assertKeyword("search \"project doc\" content", "project doc");
        }

        @Test
        @DisplayName("should extract angle-bracket content")
        void shouldExtractAngleBracketContent() {
            assertKeyword("search <budget report> for details", "budget report");
        }

        @Test
        @DisplayName("should filter stop words from plain text")
        void shouldFilterStopWords() {
            // "find the budget report for the project" → 停用词被过滤
            String result = extractKeyword("find the budget report for the project");
            assertTrue(result.contains("find"), "should keep 'find': " + result);
            assertTrue(result.contains("budget"), "should keep 'budget': " + result);
            assertTrue(result.contains("report"), "should keep 'report': " + result);
            assertTrue(result.contains("project"), "should keep 'project': " + result);
            // 停用词不应出现
            assertFalse(result.contains(" the "), "'the' should be filtered: " + result);
            assertFalse(result.contains(" for "), "'for' should be filtered: " + result);
        }

        @Test
        @DisplayName("all stop words should return original text")
        void allStopWordsShouldFallback() {
            // "the and or but" 全是停用词，应回退到原文
            assertKeyword("the and or but", "the and or but");
        }

        @Test
        @DisplayName("null message should return empty keyword")
        void nullMessageShouldReturnEmpty() {
            RagState state = new RagState();
            state.setUserMessage(null);
            state = extractor.apply(state);
            assertEquals("", state.getKeyword());
        }

        @Test
        @DisplayName("quotes take priority over stop word filtering")
        void quotesTakePriorityOverStopWords() {
            // 引号内容优先于停用词过滤
            assertKeyword("find 'the secret' document", "the secret");
        }

        @Test
        @DisplayName("mixed Chinese and English text")
        void mixedChineseAndEnglish() {
            // 中文不在停用词表中，应保留
            RagState state = new RagState();
            state.setUserMessage("搜索 budget report 文档");
            state = extractor.apply(state);
            String keyword = state.getKeyword();
            assertTrue(keyword.contains("budget"), "should contain 'budget': " + keyword);
            assertTrue(keyword.contains("report"), "should contain 'report': " + keyword);
        }

        private void assertKeyword(String message, String expected) {
            RagState state = new RagState();
            state.setUserMessage(message);
            state = extractor.apply(state);
            assertEquals(expected, state.getKeyword());
        }

        private String extractKeyword(String message) {
            RagState state = new RagState();
            state.setUserMessage(message);
            state = extractor.apply(state);
            return state.getKeyword();
        }
    }

    @Nested
    @DisplayName("General Response Node")
    class GeneralResponseNodeTests {

        private GeneralResponseNode responder;

        @BeforeEach
        void setUp() {
            responder = new GeneralResponseNode();
        }

        @Test
        @DisplayName("greeting should return response")
        void greetingShouldReturnResponse() {
            assertResponse("hello", "Hello");
            assertResponse("hi", "Hi there");
            assertResponse("thanks", "You're welcome");
        }

        @Test
        @DisplayName("unknown message should return fallback")
        void unknownShouldReturnFallback() {
            RagState state = new RagState();
            state.setUserMessage("something random");
            state = responder.apply(state);
            assertNotNull(state.getFinalAnswer());
            assertTrue(state.getFinalAnswer().contains("Lumina"));
        }

        private void assertResponse(String message, String expectedContain) {
            RagState state = new RagState();
            state.setUserMessage(message);
            state = responder.apply(state);
            assertTrue(state.getFinalAnswer().contains(expectedContain),
                    "response should contain '" + expectedContain + "', got: " + state.getFinalAnswer());
        }
    }

    @Nested
    @DisplayName("Generate Node")
    class GenerateNodeTests {

        private GenerateNode generator;

        @BeforeEach
        void setUp() {
            generator = new GenerateNode();
        }

        @Test
        @DisplayName("with docs should generate formatted answer")
        void withDocsShouldGenerateAnswer() {
            RagState state = new RagState();
            state.setKeyword("test");
            state.setRetrievedDocs(java.util.List.of("doc1 content", "doc2 content"));

            state = generator.apply(state);

            assertNotNull(state.getFinalAnswer());
            assertTrue(state.getFinalAnswer().contains("doc1 content"));
            assertTrue(state.getFinalAnswer().contains("doc2 content"));
        }

        @Test
        @DisplayName("empty docs should return friendly hint")
        void emptyDocsShouldReturnHint() {
            RagState state = new RagState();
            state.setKeyword("nonexistent");
            state.setRetrievedDocs(java.util.List.of());

            state = generator.apply(state);

            assertTrue(state.getFinalAnswer().contains("nonexistent"));
            assertTrue(state.getFinalAnswer().length() > 0);
        }

        @Test
        @DisplayName("error should return error hint")
        void errorShouldReturnErrorHint() {
            RagState state = new RagState();
            state.setKeyword("test");
            state.setError("ES timeout");

            state = generator.apply(state);

            assertTrue(state.getFinalAnswer().contains("ES timeout"));
            assertTrue(state.getFinalAnswer().contains("issue"));
        }
    }

    // ============ 3. Full RAG Workflow Tests ============

    @Nested
    @DisplayName("Full RAG Workflow")
    class RagWorkflowTests {

        @Test
        @DisplayName("retrieve path should work")
        void retrievePathShouldWork() {
            StateGraph<RagState> graph = RagWorkflowBuilder.build(
                    (keyword, needLongContext) -> "Result for: " + keyword
            );

            RagState result = graph.invoke(new RagState(Map.of(
                    RagState.KEY_USER_MESSAGE, "search budget report",
                    RagState.KEY_INDEX_NAME, "test_workspace"
            )));

            assertNotNull(result.getFinalAnswer());
            assertTrue(result.getFinalAnswer().contains("budget"));
            assertEquals(RagState.Intent.RETRIEVE, result.getIntent());
        }

        @Test
        @DisplayName("general path should return greeting")
        void generalPathShouldWork() {
            StateGraph<RagState> graph = RagWorkflowBuilder.build(
                    (keyword, needLongContext) -> "result"
            );

            RagState result = graph.invoke(new RagState(Map.of(
                    RagState.KEY_USER_MESSAGE, "hello"
            )));

            assertNotNull(result.getFinalAnswer());
            assertEquals(RagState.Intent.GENERAL, result.getIntent());
        }

        @Test
        @DisplayName("unknown path should return guidance")
        void unknownPathShouldReturnGuidance() {
            StateGraph<RagState> graph = RagWorkflowBuilder.build(
                    (keyword, needLongContext) -> "result"
            );

            RagState result = graph.invoke(new RagState(Map.of(
                    RagState.KEY_USER_MESSAGE, "weather is nice"
            )));

            assertNotNull(result.getFinalAnswer());
            assertEquals(RagState.Intent.UNKNOWN, result.getIntent());
        }

        @Test
        @DisplayName("retrieve error should be handled")
        void retrieveErrorShouldBeHandled() {
            StateGraph<RagState> graph = RagWorkflowBuilder.build(
                    (keyword, needLongContext) -> {
                        throw new RuntimeException("simulated error");
                    }
            );

            RagState result = graph.invoke(new RagState(Map.of(
                    RagState.KEY_USER_MESSAGE, "search project docs"
            )));

            assertNotNull(result.getFinalAnswer());
        }

        @Test
        @DisplayName("retrieve with no results should set error and generate hint")
        void retrieveNoResultsShouldSetError() {
            StateGraph<RagState> graph = RagWorkflowBuilder.build(
                    (keyword, needLongContext) -> "no results found. warning: empty index"
            );

            RagState result = graph.invoke(new RagState(Map.of(
                    RagState.KEY_USER_MESSAGE, "search nonexistent"
            )));

            assertNotNull(result.getFinalAnswer());
            assertNotNull(result.getError());
            assertTrue(result.getError().contains("no documents"));
        }

        @Test
        @DisplayName("question word should go through retrieve path")
        void questionWordShouldGoThroughRetrieve() {
            StateGraph<RagState> graph = RagWorkflowBuilder.build(
                    (keyword, needLongContext) -> "Answer for: " + keyword
            );

            RagState result = graph.invoke(new RagState(Map.of(
                    RagState.KEY_USER_MESSAGE, "what is the budget for Q3"
            )));

            assertEquals(RagState.Intent.RETRIEVE, result.getIntent());
            assertNotNull(result.getFinalAnswer());
            // keyword extract should have extracted "budget Q3" (filtered stop words)
            assertNotNull(result.getKeyword());
            assertFalse(result.getKeyword().isEmpty());
        }

        @Test
        @DisplayName("needLongContext flag should be passed to retriever")
        void needLongContextShouldBePassed() {
            final boolean[] capturedFlag = {false};
            StateGraph<RagState> graph = RagWorkflowBuilder.build(
                    (keyword, needLongContext) -> {
                        capturedFlag[0] = needLongContext;
                        return "result";
                    }
            );

            RagState state = new RagState(Map.of(
                    RagState.KEY_USER_MESSAGE, "search budget report",
                    RagState.KEY_NEED_LONG_CTX, true
            ));
            graph.invoke(state);

            assertTrue(capturedFlag[0], "needLongContext should be true");
        }
    }

    // ============ 4. AgentState Tests ============

    @Nested
    @DisplayName("AgentState basics")
    class AgentStateTests {

        @Test
        @DisplayName("should store and read properties")
        void shouldStoreAndRead() {
            AgentState state = new AgentState();
            state.set("name", "Lumina");
            state.set("version", 1);

            assertEquals("Lumina", state.get("name"));
            assertEquals(1, (int) state.get("version"));
        }

        @Test
        @DisplayName("missing key should return null")
        void missingKeyShouldReturnNull() {
            AgentState state = new AgentState();
            assertNull(state.get("nonexistent"));
        }

        @Test
        @DisplayName("batch update should merge")
        void batchUpdateShouldMerge() {
            AgentState state = new AgentState(Map.of("a", 1, "b", 2));
            state.update(Map.of("b", 3, "c", 4));

            assertEquals(1, (int) state.get("a"));
            assertEquals(3, (int) state.get("b"));
            assertEquals(4, (int) state.get("c"));
        }

        @Test
        @DisplayName("snapshot should be immutable")
        void snapshotShouldBeImmutable() {
            AgentState state = new AgentState();
            state.set("key", "original");
            Map<String, Object> snap = state.snapshot();
            state.set("key", "modified");

            assertEquals("original", snap.get("key"));
        }

        @Test
        @DisplayName("copy should be independent")
        void copyShouldBeIndependent() {
            AgentState original = new AgentState();
            original.set("key", "value");

            AgentState copy = original.copy();
            copy.set("key", "modified");

            assertEquals("value", original.get("key"));
            assertEquals("modified", copy.get("key"));
        }

        @Test
        @DisplayName("getOptional should return empty for missing key")
        void getOptionalShouldReturnEmptyForMissing() {
            AgentState state = new AgentState();
            assertTrue(state.getOptional("nonexistent").isEmpty());
        }

        @Test
        @DisplayName("getOptional should return value for existing key")
        void getOptionalShouldReturnValue() {
            AgentState state = new AgentState();
            state.set("name", "Lumina");
            assertEquals("Lumina", state.getOptional("name").get());
        }

        @Test
        @DisplayName("toString should contain data")
        void toStringShouldContainData() {
            AgentState state = new AgentState();
            state.set("key", "value");
            assertTrue(state.toString().contains("key"));
            assertTrue(state.toString().contains("value"));
        }

        @Test
        @DisplayName("empty constructor should create empty state")
        void emptyConstructorShouldCreateEmptyState() {
            AgentState state = new AgentState();
            assertTrue(state.snapshot().isEmpty());
        }

        @Test
        @DisplayName("null initialData should be handled gracefully")
        void nullInitialDataShouldBeHandled() {
            AgentState state = new AgentState(null);
            assertTrue(state.snapshot().isEmpty());
        }
    }

    // ============ 5. Conditional Edge Tests ============

    @Nested
    @DisplayName("Intent Routing Edge")
    class IntentRoutingEdgeTests {

        private IntentRoutingEdge routingEdge;

        @BeforeEach
        void setUp() {
            routingEdge = new IntentRoutingEdge();
        }

        @Test
        @DisplayName("RETRIEVE should route to keyword_extract")
        void retrieveShouldRouteToKeywordExtract() {
            RagState state = new RagState();
            state.setIntent(RagState.Intent.RETRIEVE);
            assertEquals(IntentRoutingEdge.KEYWORD_EXTRACT, routingEdge.evaluate(state));
        }

        @Test
        @DisplayName("GENERAL should route to general_response")
        void generalShouldRouteToGeneralResponse() {
            RagState state = new RagState();
            state.setIntent(RagState.Intent.GENERAL);
            assertEquals(IntentRoutingEdge.GENERAL_RESPONSE, routingEdge.evaluate(state));
        }

        @Test
        @DisplayName("UNKNOWN should route to error_handler")
        void unknownShouldRouteToErrorHandler() {
            RagState state = new RagState();
            state.setIntent(RagState.Intent.UNKNOWN);
            assertEquals(IntentRoutingEdge.ERROR_HANDLER, routingEdge.evaluate(state));
        }

        @Test
        @DisplayName("null intent should route to error_handler")
        void nullIntentShouldRouteToErrorHandler() {
            RagState state = new RagState();
            assertEquals(IntentRoutingEdge.ERROR_HANDLER, routingEdge.evaluate(state));
        }
    }
}
