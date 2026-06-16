package com.lumina.rag.core.agent;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
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
 * Redis Chat Memory Store 全方位测试
 * 测试覆盖：用户Memory隔离、多轮对话累积、序列化/反序列化、TTL、空数据、删除
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RedisChatMemoryStore 分布式记忆测试")
class RedisChatMemoryStoreTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Captor
    private ArgumentCaptor<String> jsonCaptor;

    private RedisChatMemoryStore memoryStore;

    @BeforeEach
    void setUp() {
        lenient().when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        memoryStore = new RedisChatMemoryStore(stringRedisTemplate);
    }

    // ==================== 1. 不同用户 Memory 隔离 ====================

    @Test
    @DisplayName("不同用户的记忆应该完全隔离，互不干扰")
    void differentUsers_shouldIsolate() {
        String userAJson = "[{\"type\":\"USER\",\"text\":\"userA_question\"},{\"type\":\"AI\",\"text\":\"answerA\"}]";
        String userBJson = "[{\"type\":\"USER\",\"text\":\"userB_question\"},{\"type\":\"AI\",\"text\":\"answerB\"}]";

        when(valueOperations.get("lumina:chat_memory:user-A")).thenReturn(userAJson);
        when(valueOperations.get("lumina:chat_memory:user-B")).thenReturn(userBJson);

        List<ChatMessage> userAMessages = memoryStore.getMessages("user-A");
        List<ChatMessage> userBMessages = memoryStore.getMessages("user-B");

        assertEquals(2, userAMessages.size());
        assertEquals("userA_question", ((UserMessage) userAMessages.get(0)).singleText());

        assertEquals(2, userBMessages.size());
        assertEquals("userB_question", ((UserMessage) userBMessages.get(0)).singleText());

        assertNotEquals(userAMessages, userBMessages);
    }

    @Test
    @DisplayName("同一个用户的不同 sessionId 应该也是隔离的")
    void sameUserDifferentSessions_shouldIsolate() {
        when(valueOperations.get("lumina:chat_memory:session-1")).thenReturn(
            "[{\"type\":\"USER\",\"text\":\"session1_msg\"}]"
        );
        when(valueOperations.get("lumina:chat_memory:session-2")).thenReturn(
            "[{\"type\":\"USER\",\"text\":\"session2_msg\"}]"
        );

        List<ChatMessage> session1 = memoryStore.getMessages("session-1");
        List<ChatMessage> session2 = memoryStore.getMessages("session-2");

        assertEquals("session1_msg", ((UserMessage) session1.get(0)).singleText());
        assertEquals("session2_msg", ((UserMessage) session2.get(0)).singleText());
    }

    // ==================== 2. 多轮对话累积 ====================

    @Test
    @DisplayName("同一会话多轮对话应该累积上下文")
    void multiTurn_shouldAccumulate() {
        memoryStore.updateMessages("conversation-1", List.of(
                UserMessage.from("hello"),
                AiMessage.from("hello, how can I help?")
        ));

        verify(valueOperations).set(
                eq("lumina:chat_memory:conversation-1"),
                jsonCaptor.capture(),
                eq(7L),
                eq(TimeUnit.DAYS)
        );
        String round1Json = jsonCaptor.getValue();
        assertTrue(round1Json.contains("hello"));

        clearInvocations(valueOperations);

        memoryStore.updateMessages("conversation-1", List.of(
                UserMessage.from("hello"),
                AiMessage.from("hello, how can I help?"),
                UserMessage.from("what is the weather?"),
                AiMessage.from("sunny today!")
        ));

        verify(valueOperations).set(
                eq("lumina:chat_memory:conversation-1"),
                jsonCaptor.capture(),
                eq(7L),
                eq(TimeUnit.DAYS)
        );
        String round2Json = jsonCaptor.getValue();
        assertTrue(round2Json.contains("what is the weather?"));
    }

    // ==================== 3. 序列化/反序列化正确性 ====================

    @Test
    @DisplayName("序列化和反序列化应该保持消息内容一致")
    void serializationRoundTrip_shouldPreserveContent() {
        List<ChatMessage> originalMessages = List.of(
                UserMessage.from("test question"),
                AiMessage.from("test answer")
        );

        memoryStore.updateMessages("roundtrip-test", originalMessages);

        verify(valueOperations).set(
                eq("lumina:chat_memory:roundtrip-test"),
                jsonCaptor.capture(),
                eq(7L),
                eq(TimeUnit.DAYS)
        );
        String savedJson = jsonCaptor.getValue();

        when(valueOperations.get("lumina:chat_memory:roundtrip-test"))
                .thenReturn(savedJson);

        List<ChatMessage> restoredMessages = memoryStore.getMessages("roundtrip-test");

        assertEquals(originalMessages.size(), restoredMessages.size());
        assertEquals(
                ((UserMessage) originalMessages.get(0)).singleText(),
                ((UserMessage) restoredMessages.get(0)).singleText()
        );
        assertEquals(
                ((AiMessage) originalMessages.get(1)).text(),
                ((AiMessage) restoredMessages.get(1)).text()
        );
    }

    // ==================== 4. TTL 验证 ====================

    @Test
    @DisplayName("记忆写入 Redis 时必须设置 7 天 TTL")
    void shouldSetCorrectTTL() {
        memoryStore.updateMessages("ttl-test", List.of(
                UserMessage.from("test")
        ));

        verify(valueOperations).set(
                eq("lumina:chat_memory:ttl-test"),
                anyString(),
                eq(7L),
                eq(TimeUnit.DAYS)
        );
    }

    // ==================== 5. 空数据处理 ====================

    @Test
    @DisplayName("Redis 中无数据时应返回空列表")
    void noData_shouldReturnEmptyList() {
        when(valueOperations.get("lumina:chat_memory:new-user"))
                .thenReturn(null);

        List<ChatMessage> messages = memoryStore.getMessages("new-user");

        assertNotNull(messages);
        assertTrue(messages.isEmpty());
    }

    @Test
    @DisplayName("Redis 中存空字符串时应返回空列表")
    void emptyString_shouldReturnEmptyList() {
        when(valueOperations.get("lumina:chat_memory:empty-user"))
                .thenReturn("");

        List<ChatMessage> messages = memoryStore.getMessages("empty-user");

        assertNotNull(messages);
        assertTrue(messages.isEmpty());
    }

    // ==================== 6. 删除记忆 ====================

    @Test
    @DisplayName("删除用户记忆时应该调用 Redis 的 delete 方法")
    void deleteMessages_shouldRemoveFromRedis() {
        memoryStore.deleteMessages("user-to-delete");

        verify(stringRedisTemplate).delete("lumina:chat_memory:user-to-delete");
    }

    // ==================== 7. 10个会话隔离测试 ====================

    @Test
    @DisplayName("10 个不同会话同时操作，记忆完全隔离")
    void multipleConcurrentSessions_shouldIsolate() {
        for (int i = 0; i < 10; i++) {
            String sessionKey = "lumina:chat_memory:session-" + i;
            String jsonData = "[{\"type\":\"USER\",\"text\":\"session-" + i + "-msg\"}]";
            when(valueOperations.get(sessionKey)).thenReturn(jsonData);
        }

        for (int i = 0; i < 10; i++) {
            List<ChatMessage> messages = memoryStore.getMessages("session-" + i);
            assertEquals(1, messages.size());
            assertTrue(((UserMessage) messages.get(0)).singleText().contains("session-" + i));
        }
    }

    // ==================== 8. JSON 格式兼容性 ====================

    @Test
    @DisplayName("JSON 序列化格式应兼容 LangChain4j 标准格式")
    void jsonFormat_shouldBeLangChain4jCompatible() {
        List<ChatMessage> messages = List.of(
                UserMessage.from("hi"),
                AiMessage.from("reply")
        );

        memoryStore.updateMessages("format-test", messages);

        verify(valueOperations).set(
                eq("lumina:chat_memory:format-test"),
                jsonCaptor.capture(),
                eq(7L),
                eq(TimeUnit.DAYS)
        );

        String json = jsonCaptor.getValue();
        assertTrue(json.contains("\"type\""));
        assertTrue(json.contains("\"USER\""));
        assertTrue(json.contains("\"AI\""));
    }
}
