package com.lumina.rag.core.agent;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;

/**
 * 【驾驭层】Agent 智能体中枢大脑
 * 仅需定义接口和人设，LangChain4j 会在底层用动态代理实现所有的思考、调度与重试逻辑！
 */
public interface LuminaAgentBrain {

    @SystemMessage({
            "你是一个极其高效的数据处理路由引擎。请严格遵守以下执行协议：",
            "1. 意图为【日常问候/算术/翻译】：直接输出最终结果。",
            "2. 意图为【查询事实/专有知识/档案】：【立刻且仅】调用外部检索工具，等待工具返回数据，禁止输出任何过渡性文本或思考过程。",
            "3. 拿到工具数据后：仅基于数据作答；若数据为空，回复“抱歉，私有数据空间中没有相关记载。”"
    })
    TokenStream chat(@MemoryId String sessionId, @UserMessage String userMessage);
}