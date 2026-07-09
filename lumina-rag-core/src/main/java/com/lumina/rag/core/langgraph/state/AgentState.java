package com.lumina.rag.core.langgraph.state;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 【LangGraph 核心】Agent 状态基类
 *
 * State 是在图中流动的共享数据对象。
 * 每个 Node 读取 State 进行计算，然后写入更新后的 State。
 *
 * 设计思路：
 * - 使用 Map 存储键值对，灵活扩展
 * - 提供类型安全的 getter 辅助方法
 * - 子类可以添加特定领域的便捷方法
 *
 * 类比理解：
 * - State 就像流水线上的"工件"，每个工位（Node）读取、加工、写入
 * - 整个流程共享同一个 State 对象，实现步骤间的数据传递
 */
public class AgentState {

    /** 内部数据存储 */
    private final Map<String, Object> data = new HashMap<>();

    public AgentState() {
    }

    public AgentState(Map<String, Object> initialData) {
        if (initialData != null) {
            this.data.putAll(initialData);
        }
    }

    /**
     * 获取属性值
     */
    @SuppressWarnings("unchecked")
    public <T> T get(String key) {
        return (T) data.get(key);
    }

    /**
     * 安全获取属性值（Optional 包装）
     */
    public <T> Optional<T> getOptional(String key) {
        return Optional.ofNullable(get(key));
    }

    /**
     * 设置属性值
     */
    public void set(String key, Object value) {
        data.put(key, value);
    }

    /**
     * 批量设置属性
     */
    public void update(Map<String, Object> updates) {
        if (updates != null) {
            data.putAll(updates);
        }
    }

    /**
     * 获取所有数据的只读快照
     */
    public Map<String, Object> snapshot() {
        return new HashMap<>(data);
    }

    /**
     * 复制当前状态（深拷贝数据）
     */
    public AgentState copy() {
        AgentState newState = new AgentState();
        newState.data.putAll(this.data);
        return newState;
    }

    @Override
    public String toString() {
        return "AgentState" + data;
    }
}
