package com.aliyun.odps.agentic.otel;

import com.aliyun.odps.agentic.llm.Usage;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 轻量<span>/指标遥测模型（<b>进程内</b>实现）。
 *
 * <p><b>重要（0.4.0 起如实标注）：</b>本模块不依赖 {@code io.opentelemetry}，
 * <b>不会</b>把数据导出到任何 OTLP 后端（Jaeger/Tempo/ARMS 等）。完成的 Span 只进入
 * 内存缓冲（{@code OtelTracer.getCompletedSpans()}）与订阅回调（{@code subscribe}），
 * 指标只累计在内存 {@link OtelMetrics}。它是一个进程内遥测总线，不是真正的 OpenTelemetry 导出器。
 *
 * <p>{@code OtelConfig.exporterEndpoint} 是预留字段，当前<b>未被消费</b>——设置它不会
 * 产生任何网络导出。如需真正的 OTLP 导出，请订阅 {@code AgentEvent} 事件流或
 * {@code OtelTracer.subscribe} 自行桥接；原生 OTLP 导出在后续版本（P2）提供。
 */
public class OpenTelemetry {

    /**
     * 遥测配置。
     *
     * @param enabled 是否启用
     * @param serviceName 服务名
     * @param exporterEndpoint <b>预留字段，当前未被消费</b>——本实现不做网络导出，设置它不产生 trace 上报
     * @param resourceAttributes 资源属性
     */
    public record OtelConfig(
        boolean enabled,
        String serviceName,
        String exporterEndpoint,
        Map<String, String> resourceAttributes
    ) {
        /**
         * 创建禁用状态配置。
         *
         * @return 禁用配置
         */
        public static OtelConfig disabled() {
            return new OtelConfig(false, "agentic-sdk", "", Map.of());
        }

        /**
         * 创建启用状态配置。
         *
         * <p>注意：不导出到任何后端；span 仅留存内存并经订阅回调分发。
         *
         * @param serviceName 服务名
         * @return 启用配置
         */
        public static OtelConfig enabled(String serviceName) {
            return new OtelConfig(true, serviceName, "", Map.of());
        }

        /**
         * 创建启用状态配置并指定导出端点（<b>预留</b>，当前不产生网络导出）。
         *
         * @param serviceName 服务名
         * @param endpoint 导出端点（预留字段，未被消费）
         * @return 启用配置
         */
        public static OtelConfig enabled(String serviceName, String endpoint) {
            return new OtelConfig(true, serviceName, endpoint, Map.of());
        }
    }

    /**
     * 遥测 Span，表示一次 LLM 调用、工具执行或其他工作单元。
     */
    public static class OtelSpan implements AutoCloseable {
        private final String name;
        private final String traceId;
        private final String spanId;
        private final long startNanos;
        private long endNanos;
        private final Map<String, Object> attributes = new LinkedHashMap<>();
        private final List<OtelEvent> events = new ArrayList<>();
        private String status = "ok";
        private String statusDescription = "";

        OtelSpan(String name, String traceId) {
            this.name = name;
            this.traceId = traceId;
            this.spanId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
            this.startNanos = System.nanoTime();
        }

        /**
         * 设置 Span 属性。
         *
         * @param key 属性名
         * @param value 属性值
         * @return 当前 Span
         */
        public OtelSpan setAttribute(String key, Object value) {
            attributes.put(key, value);
            return this;
        }

        /**
         * 向 Span 添加事件。
         *
         * @param name 事件名
         * @param attributes 事件属性
         * @return 当前 Span
         */
        public OtelSpan addEvent(String name, Map<String, Object> attributes) {
            events.add(new OtelEvent(name, System.nanoTime(), attributes));
            return this;
        }

        /**
         * 将 Span 标记为错误状态。
         *
         * @param description 错误描述
         * @return 当前 Span
         */
        public OtelSpan setError(String description) {
            this.status = "error";
            this.statusDescription = description;
            return this;
        }

        /**
         * 结束 Span 计时。
         */
        public void end() {
            this.endNanos = System.nanoTime();
        }

        /**
         * 返回 Span 持续时间，单位毫秒。
         *
         * @return 持续时间毫秒值
         */
        public long durationMs() {
            long end = endNanos > 0 ? endNanos : System.nanoTime();
            return (end - startNanos) / 1_000_000;
        }

        @Override
        public void close() {
            end();
        }

        public String name() { return name; }
        public String traceId() { return traceId; }
        public String spanId() { return spanId; }
        public Map<String, Object> attributes() { return attributes; }
        public List<OtelEvent> events() { return events; }
        public String status() { return status; }
        public String statusDescription() { return statusDescription; }
    }

    /**
     * Span 内部事件。
     *
     * @param name 事件名
     * @param timestampNanos 时间戳
     * @param attributes 事件属性
     */
    public record OtelEvent(
        String name,
        long timestampNanos,
        Map<String, Object> attributes
    ) {}

    /**
     * 遥测跟踪器。
     *
     * <p>负责创建 Span、结束 Span，并将完成后的 Span 分发给订阅者。
     */
    public static class OtelTracer {
        private final OtelConfig config;
        private final String serviceName;
        private final List<OtelSpan> completedSpans = Collections.synchronizedList(new ArrayList<>());
        private final List<SpanConsumer> consumers = new ArrayList<>();

        /**
         * 使用配置创建跟踪器。
         *
         * @param config 遥测配置
         */
        public OtelTracer(OtelConfig config) {
            this.config = config;
            this.serviceName = config.serviceName();
        }

        /**
         * 判断遥测是否已启用。
         *
         * @return 启用返回 {@code true}
         */
        public boolean isEnabled() {
            return config.enabled();
        }

        /**
         * 创建普通 Span。
         *
         * @param name Span 名称
         * @return 新建 Span
         */
        public OtelSpan startSpan(String name) {
            if (!config.enabled()) return new OtelSpan(name, "disabled");
            return new OtelSpan(name, UUID.randomUUID().toString().replace("-", "").substring(0, 32));
        }

        /**
         * 创建一次 LLM 调用 Span。
         *
         * @param provider 提供者名称
         * @param model 模型名称
         * @param sessionId 会话 ID
         * @param step 步数
         * @return 新建 Span
         */
        public OtelSpan startLlmSpan(String provider, String model, String sessionId, int step) {
            OtelSpan span = startSpan("llm.call");
            if (config.enabled()) {
                span.setAttribute("llm.provider", provider);
                span.setAttribute("llm.model", model);
                span.setAttribute("session.id", sessionId);
                span.setAttribute("session.step", step);
                span.setAttribute("service.name", serviceName);
            }
            return span;
        }

        /**
         * 创建一次工具执行 Span。
         *
         * @param tool 工具名
         * @param callId 工具调用 ID
         * @param sessionId 会话 ID
         * @return 新建 Span
         */
        public OtelSpan startToolSpan(String tool, String callId, String sessionId) {
            OtelSpan span = startSpan("tool.execute");
            if (config.enabled()) {
                span.setAttribute("tool.name", tool);
                span.setAttribute("tool.call_id", callId);
                span.setAttribute("session.id", sessionId);
                span.setAttribute("service.name", serviceName);
            }
            return span;
        }

        /**
         * 结束 Span 并记录结果。
         *
         * @param span 待结束 Span
         */
        public void endSpan(OtelSpan span) {
            span.end();
            if (config.enabled()) {
                completedSpans.add(span);
                for (SpanConsumer consumer : consumers) {
                    consumer.accept(span);
                }
            }
        }

        /**
         * 将 Token 使用量写入 Span 属性。
         *
         * @param span 目标 Span
         * @param usage 使用量信息
         */
        public void recordUsage(OtelSpan span, Usage usage) {
            if (span == null || !config.enabled()) return;
            if (usage != null) {
                span.setAttribute("llm.usage.input_tokens", usage.inputTokens());
                span.setAttribute("llm.usage.output_tokens", usage.outputTokens());
                if (usage.cacheReadInputTokens() > 0) {
                    span.setAttribute("llm.usage.cache_read_tokens", usage.cacheReadInputTokens());
                }
                if (usage.cacheCreationInputTokens() > 0) {
                    span.setAttribute("llm.usage.cache_creation_tokens", usage.cacheCreationInputTokens());
                }
            }
        }

        /**
         * 订阅完成的 Span。
         *
         * @param consumer 订阅回调
         */
        public void subscribe(SpanConsumer consumer) {
            consumers.add(consumer);
        }

        /**
         * 返回已完成 Span 的不可变副本。
         *
         * @return 完成 Span 列表
         */
        public List<OtelSpan> getCompletedSpans() { return List.copyOf(completedSpans); }

        /**
         * 清空已完成 Span 缓存。
         */
        public void clear() { completedSpans.clear(); }

        /**
         * 返回当前配置。
         *
         * @return 遥测配置
         */
        public OtelConfig getConfig() { return config; }
    }

    /**
     * 遥测指标容器。
     *
     * <p>用于记录计数器、仪表值和直方图样本。
     */
    public static class OtelMetrics {
        private final Map<String, Long> counters = new ConcurrentHashMap<>();
        private final Map<String, Double> gauges = new ConcurrentHashMap<>();
        private final Map<String, List<Double>> histograms = new ConcurrentHashMap<>();

        /**
         * 递增指定计数器。
         *
         * @param name 计数器名称
         * @param value 增量
         */
        public void increment(String name, long value) { counters.merge(name, value, Long::sum); }

        /**
         * 将指定计数器加一。
         *
         * @param name 计数器名称
         */
        public void increment(String name) { increment(name, 1); }

        /**
         * 设置仪表值。
         *
         * @param name 指标名称
         * @param value 指标值
         */
        public void setGauge(String name, double value) { gauges.put(name, value); }

        /**
         * 记录直方图样本。
         *
         * @param name 指标名称
         * @param value 样本值
         */
        public void recordHistogram(String name, double value) {
            histograms.computeIfAbsent(name, k -> Collections.synchronizedList(new ArrayList<>())).add(value);
        }

        /**
         * 获取计数器值。
         *
         * @param name 指标名称
         * @return 计数器值
         */
        public Long getCounter(String name) { return counters.get(name); }

        /**
         * 获取仪表值。
         *
         * @param name 指标名称
         * @return 仪表值
         */
        public Double getGauge(String name) { return gauges.get(name); }

        /**
         * 获取直方图平均值。
         *
         * @param name 指标名称
         * @return 平均值
         */
        public double getHistogramAvg(String name) {
            List<Double> values = histograms.get(name);
            if (values == null || values.isEmpty()) return 0.0;
            return values.stream().mapToDouble(d -> d).average().orElse(0.0);
        }

        /**
         * 获取全部计数器快照。
         *
         * @return 计数器映射
         */
        public Map<String, Long> getAllCounters() { return Map.copyOf(counters); }

        /**
         * 记录一次 LLM 调用相关指标。
         *
         * @param provider 提供者名
         * @param model 模型名
         * @param usage 使用量
         * @param durationMs 耗时毫秒数
         */
        public void recordLlmCall(String provider, String model, Usage usage, long durationMs) {
            increment("llm.calls");
            increment("llm.calls." + provider);
            increment("llm.input_tokens", usage != null ? usage.inputTokens() : 0);
            increment("llm.output_tokens", usage != null ? usage.outputTokens() : 0);
            if (usage != null && usage.cacheReadInputTokens() > 0) {
                increment("llm.cache_read_tokens", usage.cacheReadInputTokens());
            }
            recordHistogram("llm.latency_ms", durationMs);
        }

        /**
         * 记录一次工具调用相关指标。
         *
         * @param tool 工具名
         * @param durationMs 耗时毫秒数
         * @param success 是否成功
         */
        public void recordToolCall(String tool, long durationMs, boolean success) {
            increment("tool.calls");
            increment("tool.calls." + tool);
            if (!success) increment("tool.errors." + tool);
            recordHistogram("tool.latency_ms." + tool, durationMs);
        }
    }

    /**
     * 完成 Span 的订阅接口。
     */
    @FunctionalInterface
    public interface SpanConsumer {
        /**
         * 处理一个已完成的 Span。
         *
         * @param span 已完成 Span
         */
        void accept(OtelSpan span);
    }
}
