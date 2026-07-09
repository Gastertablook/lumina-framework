package com.lumina.rag.core.langgraph.graph;

import com.lumina.rag.core.langgraph.edge.ConditionalEdge;
import com.lumina.rag.core.langgraph.node.Node;
import com.lumina.rag.core.langgraph.state.AgentState;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 【LangGraph 核心】有状态图引擎
 *
 * StateGraph 是整个工作流引擎的核心，它：
 * 1. 管理所有节点（Node）—— 每个节点是一个处理步骤
 * 2. 管理所有边（Edge）—— 定义节点间的流转路径
 * 3. 驱动执行——从入口节点开始，按边定义的路径依次执行
 *
 * 设计理念（对标 LangGraph Python 版）：
 * - StateGraph：有状态图，节点间共享 State 对象
 * - addNode(name, node)：注册节点
 * - addEdge(from, to)：添加普通边（无条件路由）
 * - addConditionalEdge(from, condition, mapping)：添加条件边（条件路由）
 * - setEntryPoint(name)：设置入口节点
 * - setFinishPoint(name)：设置结束节点
 * - compile()：编译图（校验拓扑正确性）
 * - invoke(state)：执行图，返回最终状态
 *
 * 类比理解：
 * StateGraph 就像一张地铁图：
 * - 车站 = Node（处理步骤）
 * - 线路 = Edge（路径）
 * - 换乘条件 = ConditionalEdge（根据当前状态决定下一站）
 * - 乘客 = State（在地铁网络中流动的数据）
 *
 * 类型参数：
 * @param <S> 图中流动的 State 类型
 */
@Slf4j
public class StateGraph<S extends AgentState> {

    /** 节点注册表：名称 → Node */
    private final Map<String, Node<S>> nodes = new LinkedHashMap<>();

    /** 普通边：from → to（有序列表，支持多出边） */
    private final Map<String, List<String>> edges = new HashMap<>();

    /** 条件边：from → (ConditionalEdge, 目标映射) */
    private final Map<String, ConditionalEdgeEntry<S>> conditionalEdges = new HashMap<>();

    /** 入口节点名称 */
    private String entryPoint;

    /** 结束节点集合 */
    private final Set<String> finishPoints = new HashSet<>();

    /** 是否已编译 */
    private boolean compiled = false;

    /**
     * 注册一个节点
     *
     * @param name 节点名称（在同一图中必须唯一）
     * @param node 节点实现
     * @return this（链式调用）
     */
    public StateGraph<S> addNode(String name, Node<S> node) {
        if (nodes.containsKey(name)) {
            throw new IllegalArgumentException("节点 '" + name + "' 已存在");
        }
        nodes.put(name, node);
        log.debug("[StateGraph] 注册节点: {}", name);
        return this;
    }

    /**
     * 添加普通边（A → B）
     *
     * @param fromNode 源节点名称
     * @param toNode 目标节点名称
     * @return this（链式调用）
     */
    public StateGraph<S> addEdge(String fromNode, String toNode) {
        edges.computeIfAbsent(fromNode, k -> new ArrayList<>()).add(toNode);
        log.debug("[StateGraph] 添加边: {} → {}", fromNode, toNode);
        return this;
    }

    /**
     * 添加条件边
     *
     * 条件边允许根据当前 State 动态选择下一个节点。
     *
     * @param fromNode 源节点名称
     * @param condition 条件评估器（接收 State，返回目标节点名）
     * @param nodeMapping 节点映射描述（仅用于日志/可视化）
     * @return this（链式调用）
     */
    public StateGraph<S> addConditionalEdge(
            String fromNode,
            ConditionalEdge<S> condition,
            Map<String, String> nodeMapping) {
        conditionalEdges.put(fromNode, new ConditionalEdgeEntry<>(condition, nodeMapping));
        log.debug("[StateGraph] 添加条件边: {} → condition={}", fromNode, nodeMapping);
        return this;
    }

    /**
     * 设置入口节点
     */
    public StateGraph<S> setEntryPoint(String name) {
        this.entryPoint = name;
        return this;
    }

    /**
     * 设置结束节点
     */
    public StateGraph<S> setFinishPoint(String name) {
        this.finishPoints.add(name);
        return this;
    }

    /**
     * 编译图：校验配置的正确性
     * 执行 invoke 前必须编译
     *
     * @return this（链式调用）
     */
    public StateGraph<S> compile() {
        if (entryPoint == null) {
            throw new IllegalStateException("未设置入口节点（setEntryPoint）");
        }
        if (!nodes.containsKey(entryPoint)) {
            throw new IllegalStateException("入口节点 '" + entryPoint + "' 未注册");
        }

        // 校验所有边的源节点都存在
        for (String from : edges.keySet()) {
            if (!nodes.containsKey(from)) {
                throw new IllegalStateException("边的源节点 '" + from + "' 未注册");
            }
        }
        for (String from : conditionalEdges.keySet()) {
            if (!nodes.containsKey(from)) {
                throw new IllegalStateException("条件边的源节点 '" + from + "' 未注册");
            }
        }

        compiled = true;
        log.info("[StateGraph] 图编译完成，共 {} 个节点, {} 条边, {} 条条件边",
                nodes.size(), edges.size(), conditionalEdges.size());
        return this;
    }

    /**
     * 执行图
     *
     * 从入口节点开始，按边定义的路径依次执行节点，
     * 直到到达结束节点或没有出边为止。
     *
     * @param initialState 初始状态
     * @return 最终状态
     */
    public S invoke(S initialState) {
        if (!compiled) {
            throw new IllegalStateException("图尚未编译，请先调用 compile()");
        }

        log.info("[StateGraph] 开始执行，入口节点: {}", entryPoint);

        S currentState = initialState;
        String currentNode = entryPoint;

        // 防止无限循环（最大步数保护）
        int maxSteps = nodes.size() * 10;
        int stepCount = 0;

        while (currentNode != null) {
            stepCount++;
            if (stepCount > maxSteps) {
                throw new IllegalStateException(
                        "执行步数超过上限 " + maxSteps + "，可能存在无限循环");
            }

            log.debug("[StateGraph] 执行节点 [{}] (第{}步)", currentNode, stepCount);

            // 执行当前节点
            Node<S> node = nodes.get(currentNode);
            if (node == null) {
                throw new IllegalStateException("节点 '" + currentNode + "' 未注册");
            }

            currentState = node.apply(currentState);

            // 检查是否到达结束节点
            if (finishPoints.contains(currentNode)) {
                log.info("[StateGraph] 到达结束节点 [{}]，执行完成", currentNode);
                break;
            }

            // 确定下一个节点
            currentNode = determineNextNode(currentNode, currentState);

            if (currentNode != null) {
                log.debug("[StateGraph] {} → {}", getPreviousNodeName(stepCount, currentNode), currentNode);
            }
        }

        log.info("[StateGraph] 执行完成，共 {} 步", stepCount);
        return currentState;
    }

    /**
     * 确定下一个节点
     */
    private String determineNextNode(String fromNode, S state) {
        // 先检查条件边
        ConditionalEdgeEntry<S> condEntry = conditionalEdges.get(fromNode);
        if (condEntry != null) {
            String next = condEntry.condition.evaluate(state);
            log.debug("[StateGraph] 条件边评估: {} → {}", fromNode, next);
            return next;
        }

        // 再检查普通边
        List<String> nextNodes = edges.get(fromNode);
        if (nextNodes != null && !nextNodes.isEmpty()) {
            if (nextNodes.size() > 1) {
                log.warn("[StateGraph] 节点 '{}' 有多条出边（{}），将使用第一条",
                        fromNode, nextNodes);
            }
            return nextNodes.get(0);
        }

        // 没有出边 → 结束
        return null;
    }

    private String getPreviousNodeName(int stepCount, String currentNode) {
        return currentNode;
    }

    /**
     * 获取图中所有节点的名称
     */
    public Set<String> getNodeNames() {
        return Collections.unmodifiableSet(nodes.keySet());
    }

    /**
     * 获取入口节点名称
     */
    public String getEntryPoint() {
        return entryPoint;
    }

    /**
     * 获取结束节点集合
     */
    public Set<String> getFinishPoints() {
        return Collections.unmodifiableSet(finishPoints);
    }

    // ============ 内部数据结构 ============

    private static class ConditionalEdgeEntry<S extends AgentState> {
        final ConditionalEdge<S> condition;
        final Map<String, String> nodeMapping;

        ConditionalEdgeEntry(ConditionalEdge<S> condition, Map<String, String> nodeMapping) {
            this.condition = condition;
            this.nodeMapping = nodeMapping;
        }
    }
}
