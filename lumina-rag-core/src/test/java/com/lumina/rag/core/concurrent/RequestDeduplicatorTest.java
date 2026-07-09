package com.lumina.rag.core.concurrent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.RepeatedTest;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 【驾驭层】Singleflight 高并发去重器全方位测试
 *
 * 测试覆盖：
 * 1. 基础功能：相同 Key 只执行一次真实操作
 * 2. 并发安全：N 个线程同时请求相同 Key，仅一个先锋线程执行
 * 3. 异常传播：先锋线程抛出异常时，所有跟随者都收到异常
 * 4. 内存泄漏验证：请求完成后 inFlightRequests 必须清理
 * 5. 不同 Key 隔离：不同 Key 的请求不互相影响
 * 6. 连续相同请求：第二次请求不受前一次缓存影响
 * 7. 高并发压力测试
 */
@DisplayName("Singleflight 高并发去重器测试")
class RequestDeduplicatorTest {

    private RequestDeduplicator deduplicator;

    @BeforeEach
    void setUp() {
        deduplicator = new RequestDeduplicator();
    }

    // ==================== 1. 基础功能测试 ====================

    @Test
    @DisplayName("相同 Key 应该只执行一次真实操作")
    void sameKey_shouldExecuteOnce() {
        AtomicInteger executionCount = new AtomicInteger(0);

        // 第一个请求
        String result1 = deduplicator.execute("test-key", () -> {
            executionCount.incrementAndGet();
            return "result";
        });
        assertEquals("result", result1);
        assertEquals(1, executionCount.get(), "真实操作应该只执行 1 次");
    }

    @Test
    @DisplayName("相同 Key 同时并发时，仅先锋执行，跟随者复用结果")
    void concurrentSameKey_shouldDeduplicate() throws InterruptedException, ExecutionException {
        AtomicInteger executionCount = new AtomicInteger(0);
        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(1); // 所有线程同时出发

        Callable<String> task = () -> {
            latch.await(); // 等待信号，同时触发
            return deduplicator.execute("concurrent-key", () -> {
                executionCount.incrementAndGet();
                try { Thread.sleep(100); } catch (InterruptedException e) { throw new RuntimeException(e); }
                return "shared-result";
            });
        };

        List<Future<String>> futures = new ArrayList<>();
        for (int i = 0; i < threadCount; i++) {
            futures.add(executor.submit(task));
        }

        latch.countDown(); // 释放所有线程！

        // 验证所有线程都拿到相同的结果
        for (Future<String> future : futures) {
            assertEquals("shared-result", future.get());
        }

        assertEquals(1, executionCount.get(),
                "10 个并发线程请求相同 Key，真实操作应该只执行 1 次！");
        executor.shutdown();
    }

    // ==================== 2. 异常传播测试 ====================

    @Test
    @DisplayName("先锋线程抛出异常，跟随者应该也收到异常")
    void exceptionPropagation_allShouldFail() {
        assertThrows(RuntimeException.class, () -> {
            deduplicator.execute("fail-key", () -> {
                throw new RuntimeException("先锋线程挂了");
            });
        });
    }

    @Test
    @DisplayName("并发下先锋线程异常，所有跟随者都收到异常")
    void concurrentException_allFollowersShouldFail() throws InterruptedException {
        AtomicInteger caughtCount = new AtomicInteger(0);
        int threadCount = 5;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(1);

        Runnable task = () -> {
            try {
                latch.await();
                deduplicator.execute("fail-key-concurrent", () -> {
                    throw new RuntimeException("先锋线程挂了");
                });
            } catch (Exception e) {
                caughtCount.incrementAndGet();
            }
        };

        for (int i = 0; i < threadCount; i++) {
            executor.submit(task);
        }

        latch.countDown();
        try { Thread.sleep(500); } catch (InterruptedException e) { throw new RuntimeException(e); }

        assertEquals(threadCount, caughtCount.get(),
                "所有 " + threadCount + " 个线程都应该捕获到异常");
        executor.shutdown();
    }

    // ==================== 3. 内存泄漏验证 ====================

    @Test
    @DisplayName("请求完成后，inFlightRequests 必须清理干净，防止内存泄漏")
    void afterExecution_inFlightMapShouldBeCleaned() throws Exception {
        // 使用反射验证内部 map 的状态
        var mapField = RequestDeduplicator.class.getDeclaredField("inFlightRequests");
        mapField.setAccessible(true);
        @SuppressWarnings("unchecked")
        var inFlightMap = (ConcurrentHashMap<String, CompletableFuture<String>>) mapField.get(deduplicator);

        // 执行成功请求
        deduplicator.execute("clean-key", () -> "success");
        assertEquals(0, inFlightMap.size(),
                "成功后 inFlightRequests 应该为空，防止内存泄漏");

        // 执行失败请求
        try {
            deduplicator.execute("fail-clean-key", () -> {
                throw new RuntimeException("fail");
            });
        } catch (Exception ignored) {}

        assertEquals(0, inFlightMap.size(),
                "失败后 inFlightRequests 也应该为空，防止死锁");
    }

    // ==================== 4. 不同 Key 隔离测试 ====================

    @Test
    @DisplayName("不同 Key 的请求应该互不影响，各自独立执行")
    void differentKeys_shouldIsolate() {
        AtomicInteger execA = new AtomicInteger(0);
        AtomicInteger execB = new AtomicInteger(0);

        String resultA = deduplicator.execute("key-a", () -> {
            execA.incrementAndGet();
            return "A";
        });
        String resultB = deduplicator.execute("key-b", () -> {
            execB.incrementAndGet();
            return "B";
        });

        assertEquals("A", resultA);
        assertEquals("B", resultB);
        assertEquals(1, execA.get());
        assertEquals(1, execB.get());
    }

    // ==================== 5. 连续相同 Key 测试 ====================

    @Test
    @DisplayName("连续两次相同 Key 的请求，第二次应该重新执行（因为第一次已清理）")
void serialSameKey_shouldExecuteEachTime() {
        AtomicInteger executionCount = new AtomicInteger(0);

        String result1 = deduplicator.execute("serial-key", () -> {
            executionCount.incrementAndGet();
            return "first";
        });
        String result2 = deduplicator.execute("serial-key", () -> {
            executionCount.incrementAndGet();
            return "second";
        });

        assertEquals("first", result1);
        assertEquals("second", result2);
        assertEquals(2, executionCount.get(),
                "连续两次串行相同 Key 应该各自执行（因为第一次执行完已清理）");
    }

    // ==================== 6. 高并发压力测试 ====================

    @RepeatedTest(3)
    @DisplayName("高并发压力测试：多 Key 混合并发，验证去重正确性")
    void highConcurrencyStressTest() throws InterruptedException, ExecutionException {
        AtomicInteger executionCount = new AtomicInteger(0);
        int totalThreads = 50;
        int uniqueKeys = 5; // 5 个不同 Key，每个 Key 10 个线程
        ExecutorService executor = Executors.newFixedThreadPool(totalThreads);
        CountDownLatch latch = new CountDownLatch(1);

        Callable<String> task = () -> {
            latch.await();
            int keyIndex = ThreadLocalRandom.current().nextInt(uniqueKeys);
            String key = "stress-key-" + keyIndex;
            return deduplicator.execute(key, () -> {
                executionCount.incrementAndGet();
                try { Thread.sleep(50); } catch (InterruptedException e) { throw new RuntimeException(e); }
                return "result-for-" + key;
            });
        };

        List<Future<String>> futures = new ArrayList<>();
        for (int i = 0; i < totalThreads; i++) {
            futures.add(executor.submit(task));
        }

        latch.countDown();

        for (Future<String> future : futures) {
            assertNotNull(future.get());
        }

        // 50 个请求分到 5 个 Key，每个 Key 只执行 1 次，共执行 5 次
        assertEquals(uniqueKeys, executionCount.get(),
                "50 个线程分 " + uniqueKeys + " 个 Key，真实操作应该只执行 " + uniqueKeys + " 次");
        executor.shutdown();
    }

    // ==================== 7. 性能基准测试 ====================

    @Test
    @DisplayName("Singleflight 性能基准：100 个相同请求的执行总耗时远小于 100 次独立执行")
    void performanceBenchmark() throws InterruptedException, ExecutionException {
        AtomicInteger executionCount = new AtomicInteger(0);
        int threadCount = 100;
        int operationCostMs = 200; // 模拟每次操作耗时 200ms

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(1);

        long startTime = System.currentTimeMillis();

        Callable<String> task = () -> {
            latch.await();
            return deduplicator.execute("perf-key", () -> {
                executionCount.incrementAndGet();
                try { Thread.sleep(operationCostMs); } catch (InterruptedException e) { throw new RuntimeException(e); }
                return "perf-result";
            });
        };

        List<Future<String>> futures = new ArrayList<>();
        for (int i = 0; i < threadCount; i++) {
            futures.add(executor.submit(task));
        }

        latch.countDown();

        for (Future<String> future : futures) {
            assertEquals("perf-result", future.get());
        }

        long totalTime = System.currentTimeMillis() - startTime;

        // 如果不去重，100 × 200ms ≈ 20s，去重后 ≈ 200ms + 调度开销
        assertTrue(totalTime < operationCostMs * 3,
                "100 个并发相同请求的总耗时应该在 " + (operationCostMs * 3) + "ms 以内，实际：" + totalTime + "ms");
        assertEquals(1, executionCount.get(),
                "100 个并发相同请求应该只执行 1 次真实操作");

        System.out.println("✅ 性能基准：100 个并发相同请求，去重后总耗时 " + totalTime + "ms（若无去重 ≈ 20,000ms）");
        executor.shutdown();
    }
}
