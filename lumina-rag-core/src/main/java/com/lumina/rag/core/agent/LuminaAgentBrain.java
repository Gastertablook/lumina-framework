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
            "你是一个具备长期记忆的企业级智能核心。请严格执行以下四条独立指令：",
            "1. 【记忆提取】：你拥有当前用户的完整对话历史。当提问依赖历史上下文时，请直接提取记忆连贯作答。",
            "2. 【常规任务】：处理不依赖外部私有数据的通用逻辑（如问候、基础算术、翻译）时，直接输出最终结果。",
            "3. 【专有事实】：当提问涉及特定实体、业务数据或专有事实时，【仅限】静默调用检索工具获取数据。基于工具返回的数据作答；若无数据，仅输出'抱歉，私有数据空间中没有相关记载。'",
            "4. 【混合意图最高红线】：若提问同时包含上述多项意图，你必须【首要且仅】触发工具调用指令去获取底层数据。待获取到完整检索数据后，你在生成最终回复时，【必须】兼顾两方面：首先回应用户的【记忆提取】与【常规任务】，然后再根据资料回答事实问题！"
    })
    TokenStream chat(@MemoryId String sessionId, @UserMessage String userMessage);
}