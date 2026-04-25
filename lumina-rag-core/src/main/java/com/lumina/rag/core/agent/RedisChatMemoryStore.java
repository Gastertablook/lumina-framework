package com.lumina.rag.core.agent;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 【驾驭层】分布式全局记忆中心
 * 彻底告别单机 JVM 内存！让 Agent 拥有跨节点、跨设备的永生记忆！
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisChatMemoryStore implements ChatMemoryStore {

    private final StringRedisTemplate stringRedisTemplate;
    private static final String MEMORY_PREFIX = "lumina:chat_memory:";

    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        String key = MEMORY_PREFIX + memoryId;
        String json = stringRedisTemplate.opsForValue().get(key);
        if (json == null || json.isEmpty()) {
            return new ArrayList<>();
        }
        // 反序列化历史对话
        return ChatMessageDeserializer.messagesFromJson(json);
    }

    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        String key = MEMORY_PREFIX + memoryId;
        // 序列化最新对话并存入 Redis，设置 7 天过期时间
        String json = ChatMessageSerializer.messagesToJson(messages);
        stringRedisTemplate.opsForValue().set(key, json, 7, TimeUnit.DAYS);
    }

    @Override
    public void deleteMessages(Object memoryId) {
        stringRedisTemplate.delete(MEMORY_PREFIX + memoryId);
    }
}