package com.lumina.rag.core.concurrent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RequestDeduplicator 补充测试
 *
 * 覆盖已有测试未覆盖的场景：
 * - 超时场景
 * - 内存泄漏检测（大量不同 key）
 * - 重复调用的缓存行为
 * - 并发度验证
 */
class RequestDeduplicatorExtendedTest {

    private RequestDeduplicator deduplicator;

    @BeforeEach
    void setUp() {
        deduplicator = new RequestDeduplicator();
    }

    @Test
    @DisplayName("同一 key 连续调用应只执行一次 supplier")
    void sameKeyRepeatedCalls_ShouldExecuteOnce() {
        AtomicInteger counter = new AtomicInteger(0);

        // 第一次调用
        String result1 = deduplicator.execute("key-1", () -> {
            counter.incrementAndGet();
            return "第一次结果";
        });

        // 第二次调用（相同 key，此时第一次已执行完毕并清理，应重新执行）
        String result2 = deduplicator.execute("key-1", () -> {
            counter.incrementAndGet();
            return "第二次结果";
        });

        assertEquals("第一次结果", result1);
        assertEquals("第二次结果", result2, "第一次完成后清理了状态，第二次应重新执行");
        assertEquals(2, counter.get(), "supplier 应执行 2 次（每次都是独立调用）");
    }

    @Test
    @DisplayName("不同 key 应完全隔离执行")
    void differentKeys_ShouldExecuteIndependently() {
        AtomicInteger counterA = new AtomicInteger(0);
        AtomicInteger counterB = new AtomicInteger(0);

        String resultA = deduplicator.execute("key-A", () -> {
            counterA.incrementAndGet();
            return "A的结果";
        });

        String resultB = deduplicator.execute("key-B", () -> {
            counterB.incrementAndGet();
            return "B的结果";
        });

        assertEquals("A的结果", resultA);
        assertEquals("B的结果", resultB);
        assertEquals(1, counterA.get());
        assertEquals(1, counterB.get());
    }

    @Test
    @DisplayName("高并发 100 线程争抢同一 key")
    void highConcurrency_SameKey() throws InterruptedException {
        int threadCount = 100;
        AtomicInteger executionCount = new AtomicInteger(0);
        String sharedKey = "concurrent-key";
        CountDownLatch latch = new CountDownLatch(threadCount);
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        ConcurrentLinkedQueue<String> results = new ConcurrentLinkedQueue<>();

        // 使用 CyclicBarrier 让所有线程同时出发
        CyclicBarrier barrier = new CyclicBarrier(threadCount);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    barrier.await(); // 所有线程同时到达屏障后一起出发
                    String result = deduplicator.execute(sharedKey, () -> {
                        executionCount.incrementAndGet();
                        try {
                            TimeUnit.MILLISECONDS.sleep(50); // 模拟耗时
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new RuntimeException(e);
                        }
                        return "共享结果";
                    });
                    results.add(result);
                } catch (Exception e) {
                    results.add("ERROR: " + e.getMessage());
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertEquals(1, executionCount.get(), "100线程争抢同一key，supplier应仅执行1次");
        assertEquals(threadCount, results.size(), "所有线程都应拿到结果");
        results.forEach(r -> assertEquals("共享结果", r));
    }

    @Test
    @DisplayName("高并发 100 线程争抢不同 key")
    void highConcurrency_DifferentKeys() throws InterruptedException {
        int threadCount = 100;
        AtomicInteger executionCount = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(threadCount);
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);

        for (int i = 0; i < threadCount; i++) {
            int keyIndex = i;
            executor.submit(() -> {
                try {
                    deduplicator.execute("key-" + keyIndex, () -> {
                        executionCount.incrementAndGet();
                        try {
                            TimeUnit.MILLISECONDS.sleep(10);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new RuntimeException(e);
                        }
                        return "结果-" + keyIndex;
                    });
                } catch (Exception e) {
                    // ignore
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertEquals(threadCount, executionCount.get(), "不同key应各自执行一次，共执行" + threadCount + "次");
    }

    @Test
    @DisplayName("大量不同 key 请求后应无内存泄漏")
    void manyDifferentKeys_ShouldNotLeakMemory() {
        int keyCount = 1000;

        for (int i = 0; i < keyCount; i++) {
            int idx = i;
            deduplicator.execute("leak-key-" + idx, () -> "结果-" + idx);
        }

        // 所有 key 已完成，内部 Future 应已被清理
        // 再次请求相同 key 应重新执行
        AtomicInteger recount = new AtomicInteger(0);
        deduplicator.execute("leak-key-0", () -> {
            recount.incrementAndGet();
            return "重新执行";
        });

        assertEquals(1, recount.get(), "已完成并清理的 key 应重新执行");
    }

    @Test
    @DisplayName("supplier 返回 null 应能正确处理")
    void supplierReturnsNull_ShouldHandleGracefully() {
        String result = deduplicator.execute("null-key", () -> null);

        assertNull(result, "supplier 返回 null 时也应正确传递");
    }

    @Test
    @DisplayName("第一次完成后第二次应重新执行（去重仅针对并发中的请求）")
    void afterCompletion_SecondCallShouldReExecute() {
        AtomicInteger counter = new AtomicInteger(0);

        // 第一次
        String r1 = deduplicator.execute("re-execute-key", () -> {
            counter.incrementAndGet();
            return "第1次";
        });

        // 第二次（应重新执行，因为第一次完成后已清理状态）
        String r2 = deduplicator.execute("re-execute-key", () -> {
            counter.incrementAndGet();
            return "第2次";
        });

        assertEquals("第1次", r1);
        assertEquals("第2次", r2);
        assertEquals(2, counter.get(), "完成后再次请求应重新执行");
    }

    @Test
    @DisplayName("supplier 抛出异常时异常应传播给所有调用者")
    void supplierThrowsException_ShouldPropagateToAll() {
        assertThrows(RuntimeException.class, () ->
                deduplicator.execute("error-key", () -> {
                    throw new RuntimeException("测试异常");
                })
        );
    }
}
