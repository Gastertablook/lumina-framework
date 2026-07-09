package com.lumina.rag.core.langgraph.node;

import com.lumina.rag.core.langgraph.state.AgentState;

/**
 * 【LangGraph 核心】图节点接口
 *
 * Node 是工作流中的一个处理步骤。
 * 每个 Node 接收当前 State，执行计算，然后返回更新后的 State。
 *
 * 类比理解：
 * - Node = 流水线上的一个工位
 * - State = 流水线上的工件
 * - 每个工位读取工件，加工，然后放回流到下个工位
 *
 * 类型参数：
 * @param <S> 此节点处理的 State 类型，使得子类可以使用类型安全的访问器
 */
@FunctionalInterface
public interface Node<S extends AgentState> {

    /**
     * 执行节点逻辑
     *
     * @param state 当前状态（包含之前所有节点的输出）
     * @return 更新后的状态（可以返回同一个 state 对象，也可以返回新对象）
     */
    S apply(S state);
}
