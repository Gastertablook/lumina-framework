package com.lumina.rag.core.tracing;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Scope;
import lombok.extern.slf4j.Slf4j;

/**
 * 【驾驭层】Lumina 全链路追踪器
 *
 * 基于 OpenTelemetry API，为 RAG 引擎的每一次对话和检索提供全链路可观测性。
 * 数据通过 OTLP 协议发送到 Langfuse（或任何兼容的 Observability 后端）。
 *
 * 设计原则：
 * - 无侵入：调用方只需 try-finally 包裹，不需要继承或实现接口
 * - 轻量级：OpenTelemetry 的 Span 是线程安全的，性能开销极低
 * - 可切换：通过修改 OTLP 端点配置，可切换到 Jaeger、Zipkin 等
 *
 * 用法：
 * {@code
 *   Span span = LuminaTracer.start("lumina.chat")
 *       .setAttribute("query", query)
 *       .setAttribute("sessionId", sessionId);
 *   try (Scope scope = span.makeCurrent()) {
 *       // ... 业务逻辑 ...
 *       span.setAttribute("result.length", result.length());
 *   } catch (Exception e) {
 *       span.recordException(e);
 *       span.setStatus(StatusCode.ERROR);
 *   } finally {
 *       span.end();
 *   }
 * }
 */
@Slf4j
public class LuminaTracer {

    private static final String INSTRUMENTATION_NAME = "com.lumina.rag.core";
    private static final String INSTRUMENTATION_VERSION = "1.0.0";

    private static volatile Tracer tracer = null;

    /**
     * 获取 OpenTelemetry Tracer 实例（懒加载）
     */
    private static Tracer getTracer() {
        if (tracer == null) {
            synchronized (LuminaTracer.class) {
                if (tracer == null) {
                    OpenTelemetry otel = GlobalOpenTelemetry.get();
                    tracer = otel.getTracer(INSTRUMENTATION_NAME, INSTRUMENTATION_VERSION);
                    log.info("[驾驭层] OpenTelemetry Tracer 已初始化");
                }
            }
        }
        return tracer;
    }

    /**
     * 开始一个新的 Span（根 Span，无父级）
     *
     * @param spanName Span 名称，如 "lumina.chat"、"lumina.retrieve"
     * @return 创建的 Span 实例
     */
    public static Span start(String spanName) {
        return getTracer().spanBuilder(spanName)
                .setSpanKind(SpanKind.INTERNAL)
                .startSpan();
    }

    /**
     * 开始一个新的 Span（带父级上下文）
     *
     * @param spanName Span 名称
     * @param parentSpan 父级 Span
     * @return 创建的 Span 实例
     */
    public static Span start(String spanName, Span parentSpan) {
        return getTracer().spanBuilder(spanName)
                .setSpanKind(SpanKind.INTERNAL)
                .setParent(io.opentelemetry.context.Context.current().with(parentSpan))
                .startSpan();
    }

    /**
     * 快速记录一个事件（不创建 Span，只打日志标记）
     *
     * @param span 当前 Span
     * @param eventName 事件名称
     */
    public static void event(Span span, String eventName) {
        span.addEvent(eventName);
    }

    /**
     * 快速记录一个带属性的事件
     *
     * @param span 当前 Span
     * @param eventName 事件名称
     * @param key 属性键
     * @param value 属性值
     */
    public static void event(Span span, String eventName, String key, String value) {
        span.addEvent(eventName, io.opentelemetry.api.common.Attributes.of(
                io.opentelemetry.api.common.AttributeKey.stringKey(key), value
        ));
    }

    /**
     * 标记 Span 为成功完成
     */
    public static void end(Span span) {
        if (span != null) {
            span.setStatus(StatusCode.OK);
            span.end();
        }
    }

    /**
     * 标记 Span 为异常结束
     */
    public static void endWithError(Span span, Throwable error) {
        if (span != null) {
            span.recordException(error);
            span.setStatus(StatusCode.ERROR, error.getMessage());
            span.end();
        }
    }

    /**
     * 设置多个属性到 Span
     */
    public static Span setAttributes(Span span, String... keyValues) {
        if (span == null || keyValues == null) return span;
        for (int i = 0; i < keyValues.length - 1; i += 2) {
            if (keyValues[i] != null && keyValues[i + 1] != null) {
                span.setAttribute(keyValues[i], keyValues[i + 1]);
            }
        }
        return span;
    }

    /**
     * 检查 OpenTelemetry 是否已初始化（即是否有 OTLP Exporter 配置）
     */
    public static boolean isEnabled() {
        try {
            return GlobalOpenTelemetry.get() != null;
        } catch (Exception e) {
            return false;
        }
    }
}
