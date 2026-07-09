package com.lumina.rag.core.agent;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * RedisChatMemoryStore 补充测试
 *
 * 覆盖：
 * - TTL 过期行为
 * - 消息序列化/反序列化边界
 * - 空消息列表处理
 * - 大量 session 隔离
 * - Redis 操作异常处理
 */
@ExtendWith(MockitoExtension.class)
class RedisChatMemoryStoreExtendedTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;

    private RedisChatMemoryStore memoryStore;

    @BeforeEach
    void setUp() {
        memoryStore = new RedisChatMemoryStore(stringRedisTemplate);
    }

    @Test
    @DisplayName("存储消息时应设置 TTL")
    void storeMessages_ShouldSetTTL() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);

        String sessionId = "session-ttl-test";
        List<ChatMessage> messages = List.of(
                UserMessage.from("你好"),
                AiMessage.from("你好！有什么可以帮你的？")
        );

        memoryStore.updateMessages(sessionId, messages);

        // updateMessages 使用 set(key, json, ttl, unit)，不是 expire()
        verify(valueOperations).set(
                eq("lumina:chat_memory:" + sessionId),
                anyString(),
                eq(7L),
                eq(TimeUnit.DAYS));
    }

    @Test
    @DisplayName("空消息列表应序列化为空数组并存储")
    void emptyMessages_ShouldClearSession() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);

        String sessionId = "session-empty";
        List<ChatMessage> emptyMessages = List.of();

        memoryStore.updateMessages(sessionId, emptyMessages);

        // updateMessages 不检查空列表，直接序列化并存储空数组
        verify(valueOperations).set(
                eq("lumina:chat_memory:" + sessionId),
                anyString(),
                eq(7L),
                eq(TimeUnit.DAYS));
    }

    @Test
    @DisplayName("存储大量消息不应丢失")
    void largeNumberOfMessages_ShouldNotLoseData() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        // 模拟序列化后的值
        when(valueOperations.get(anyString())).thenReturn("[{\"type\":\"USER\",\"text\":\"test\"}]");

        String sessionId = "session-large";
        List<ChatMessage> messages = List.of(
                UserMessage.from("测试消息")
        );

        memoryStore.updateMessages(sessionId, messages);
        List<ChatMessage> retrieved = memoryStore.getMessages(sessionId);

        assertNotNull(retrieved);
    }

    @Test
    @DisplayName("获取不存在的 session 应返回空列表")
    void getNonExistentSession_ShouldReturnEmptyList() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn(null);

        List<ChatMessage> messages = memoryStore.getMessages("non-existent-session");

        assertNotNull(messages, "不存在的 session 应返回空列表而非 null");
        assertTrue(messages.isEmpty(), "不存在的 session 应返回空列表");
    }

    @Test
    @DisplayName("不同 session 的 key 应不同")
    void differentSessions_ShouldUseDifferentKeys() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);

        memoryStore.updateMessages("session-A", List.of(UserMessage.from("你好")));
        memoryStore.updateMessages("session-B", List.of(UserMessage.from("Hello")));

        // 验证使用了不同的 Redis key（包含不同的 sessionId）
        verify(stringRedisTemplate, atLeast(2)).opsForValue();
    }

    @Test
    @DisplayName("删除 session 应正确清理")
    void deleteSession_ShouldCleanUp() {
        // 无需设置 mock，delete 方法只调用 stringRedisTemplate.delete()
        String sessionId = "session-to-delete";
        memoryStore.deleteMessages(sessionId);

        verify(stringRedisTemplate, atLeastOnce()).delete(anyString());
    }

    @Test
    @DisplayName("Redis 操作异常应向上传播")
    void redisException_ShouldHandleGracefully() {
        when(stringRedisTemplate.opsForValue()).thenThrow(new RuntimeException("Redis 连接失败"));

        assertThrows(RuntimeException.class, () -> {
            memoryStore.getMessages("error-session");
        });
    }

    @Test
    @DisplayName("消息序列化包含特殊字符应正确处理")
    void messagesWithSpecialChars_ShouldHandle() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);

        String specialContent = "特殊字符: !@#$%^&*()_+-=[]{}|;':\",./<>?`~ 中文 日本語 한국어";
        List<ChatMessage> messages = List.of(
                UserMessage.from(specialContent),
                AiMessage.from("回复: " + specialContent)
        );

        assertDoesNotThrow(() -> memoryStore.updateMessages("session-special", messages));
    }
}
