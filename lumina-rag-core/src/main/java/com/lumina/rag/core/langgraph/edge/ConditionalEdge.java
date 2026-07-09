package com.lumina.rag.core.langgraph.edge;

import com.lumina.rag.core.langgraph.state.AgentState;

/**
 * 【LangGraph 核心】条件边接口
 *
 * 条件边根据当前 State 的内容，决定走哪条路径。
 * 实现了"条件路由"——让工作流可以根据数据动态选择下一步。
 *
 * 类比理解：
 * - 普通边 = "做完A，下一步一定是B"
 * - 条件边 = "做完A，如果结果是X走B，是Y走C，是Z走D"
 *
 * 类型参数：
 * @param <S> State 类型
 */
@FunctionalInterface
public interface ConditionalEdge<S extends AgentState> {

    /**
     * 根据当前状态，决定下一个节点的名称
     *
     * @param state 当前状态
     * @return 下一个节点的名称（必须是在图中注册的节点名）
     */
    String evaluate(S state);
}
