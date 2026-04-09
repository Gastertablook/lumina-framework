package com.lumina.rag.core.concurrent;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 驾驭层：高并发请求去重器 (Singleflight)
 * 不再局限于电商提问，可用于护航任何极其耗时的大模型调用或向量检索。
 */
@Slf4j
@Component
public class RequestDeduplicator {

    private final ConcurrentHashMap<String, CompletableFuture<String>> inFlightRequests = new ConcurrentHashMap<>();

    /**
     * @param deduplicationKey 去重标识 (例如用户Query的MD5)
     * @param heavyOperation 真实要执行的耗时逻辑 (调用大模型)
     */
    public String execute(String deduplicationKey, Supplier<String> heavyOperation) {
        CompletableFuture<String> newPromise = new CompletableFuture<>();
        CompletableFuture<String> existingPromise = inFlightRequests.putIfAbsent(deduplicationKey, newPromise);

        if (existingPromise != null) {
            log.info("触发 Singleflight 护城河，复用计算中结果, Key: {}", deduplicationKey);
            try {
                return existingPromise.get(); // 优雅挂起，等待先锋请求完成
            } catch (Exception e) {
                throw new RuntimeException("等待复用结果异常", e);
            }
        }

        try {
            // 我是先锋请求，执行真实的耗时逻辑
            String result = heavyOperation.get();
            newPromise.complete(result);
            return result;
        } catch (Exception e) {
            newPromise.completeExceptionally(e);
            throw e;
        } finally {
            // 无论成功失败，必须清理字典，防止内存泄漏和死锁 (驾驭工程：垃圾回收)
            inFlightRequests.remove(deduplicationKey);
        }
    }
}