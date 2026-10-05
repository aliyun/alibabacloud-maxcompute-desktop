package com.aliyun.odps.agentic.otel;

import org.junit.jupiter.api.*;
import com.aliyun.odps.agentic.otel.OpenTelemetry.*;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for OpenTelemetry integration — faithful to opencode's experimental_telemetry.
 */
class OpenTelemetryTest {

    @Test
    void disabledConfigCreatesNoOpTracer() {
        OtelConfig config = OtelConfig.disabled();
        assertFalse(config.enabled());

        OtelTracer tracer = new OtelTracer(config);
        assertFalse(tracer.isEnabled());
    }

    @Test
    void enabledConfigCreatesWorkingTracer() {
        OtelConfig config = OtelConfig.enabled("test-service");
        assertTrue(config.enabled());
        assertEquals("test-service", config.serviceName());
    }

    @Test
    void spanCreationAndEnd() {
        OtelTracer tracer = new OtelTracer(OtelConfig.enabled("test"));
        OtelSpan span = tracer.startSpan("test.operation");
        assertNotNull(span);
        assertEquals("test.operation", span.name());

        // Duration should be measurable
        span.setAttribute("key", "value");
        tracer.endSpan(span);
        assertTrue(span.durationMs() >= 0);
        assertEquals("ok", span.status());
    }

    @Test
    void spanWithAttributes() {
        OtelTracer tracer = new OtelTracer(OtelConfig.enabled("test"));
        OtelSpan span = tracer.startLlmSpan("anthropic", "claude-sonnet-4-20250514", "sess-123", 5);

        assertEquals("llm.call", span.name());
        assertEquals("anthropic", span.attributes().get("llm.provider"));
        assertEquals("claude-sonnet-4-20250514", span.attributes().get("llm.model"));
        assertEquals("sess-123", span.attributes().get("session.id"));
        assertEquals(5, span.attributes().get("session.step"));

        tracer.endSpan(span);
    }

    @Test
    void toolSpanCreation() {
        OtelTracer tracer = new OtelTracer(OtelConfig.enabled("test"));
        OtelSpan span = tracer.startToolSpan("shell", "call-abc", "sess-456");
        assertEquals("tool.execute", span.name());
        assertEquals("shell", span.attributes().get("tool.name"));
        tracer.endSpan(span);
    }

    @Test
    void spanErrorStatus() {
        OtelTracer tracer = new OtelTracer(OtelConfig.enabled("test"));
        OtelSpan span = tracer.startSpan("test.op");
        span.setError("something went wrong");
        assertEquals("error", span.status());
        assertEquals("something went wrong", span.statusDescription());
        tracer.endSpan(span);
    }

    @Test
    void spanEvents() {
        OtelTracer tracer = new OtelTracer(OtelConfig.enabled("test"));
        OtelSpan span = tracer.startSpan("test.op");
        span.addEvent("cache_hit", Map.of("key", "abc"));
        assertEquals(1, span.events().size());
        assertEquals("cache_hit", span.events().get(0).name());
        tracer.endSpan(span);
    }

    @Test
    void spanAutoClose() {
        OtelTracer tracer = new OtelTracer(OtelConfig.enabled("test"));
        try (OtelSpan span = tracer.startSpan("test.autoclose")) {
            span.setAttribute("auto", true);
        }
        // Auto-close should call end()
    }

    @Test
    void completedSpansTracking() {
        OtelTracer tracer = new OtelTracer(OtelConfig.enabled("test"));
        tracer.endSpan(tracer.startSpan("op1"));
        tracer.endSpan(tracer.startSpan("op2"));
        assertEquals(2, tracer.getCompletedSpans().size());
    }

    @Test
    void spanConsumerSubscription() {
        OtelTracer tracer = new OtelTracer(OtelConfig.enabled("test"));
        List<OtelSpan> received = new java.util.ArrayList<>();
        tracer.subscribe(received::add);

        tracer.endSpan(tracer.startSpan("op1"));
        assertEquals(1, received.size());
        assertEquals("op1", received.get(0).name());
    }

    @Test
    void clearCompletedSpans() {
        OtelTracer tracer = new OtelTracer(OtelConfig.enabled("test"));
        tracer.endSpan(tracer.startSpan("op1"));
        tracer.clear();
        assertTrue(tracer.getCompletedSpans().isEmpty());
    }

    @Test
    void disabledTracerRecordsNothing() {
        OtelTracer tracer = new OtelTracer(OtelConfig.disabled());
        OtelSpan span = tracer.startSpan("op");
        span.setAttribute("key", "val");
        tracer.endSpan(span);
        assertTrue(tracer.getCompletedSpans().isEmpty());
    }

    @Test
    void metricsIncrement() {
        OtelMetrics metrics = new OtelMetrics();
        metrics.increment("llm.calls");
        metrics.increment("llm.calls");
        assertEquals(2L, metrics.getCounter("llm.calls"));
    }

    @Test
    void metricsIncrementByValue() {
        OtelMetrics metrics = new OtelMetrics();
        metrics.increment("llm.input_tokens", 150);
        assertEquals(150L, metrics.getCounter("llm.input_tokens"));
    }

    @Test
    void metricsGauge() {
        OtelMetrics metrics = new OtelMetrics();
        metrics.setGauge("active_sessions", 5.0);
        assertEquals(5.0, metrics.getGauge("active_sessions"));
    }

    @Test
    void metricsHistogram() {
        OtelMetrics metrics = new OtelMetrics();
        metrics.recordHistogram("llm.latency_ms", 100.0);
        metrics.recordHistogram("llm.latency_ms", 200.0);
        assertEquals(150.0, metrics.getHistogramAvg("llm.latency_ms"), 0.01);
    }

    @Test
    void metricsRecordLlmCall() {
        OtelMetrics metrics = new OtelMetrics();
        com.aliyun.odps.agentic.llm.Usage usage = new com.aliyun.odps.agentic.llm.Usage(100, 50, 30, 10, 0);
        metrics.recordLlmCall("anthropic", "claude-sonnet-4-20250514", usage, 1500);
        assertEquals(1L, metrics.getCounter("llm.calls"));
        assertEquals(1L, metrics.getCounter("llm.calls.anthropic"));
        assertEquals(100L, metrics.getCounter("llm.input_tokens"));
        assertEquals(50L, metrics.getCounter("llm.output_tokens"));
        assertEquals(30L, metrics.getCounter("llm.cache_read_tokens"));
    }

    @Test
    void metricsRecordToolCall() {
        OtelMetrics metrics = new OtelMetrics();
        metrics.recordToolCall("shell", 500, true);
        metrics.recordToolCall("shell", 300, false);
        assertEquals(2L, metrics.getCounter("tool.calls"));
        assertEquals(2L, metrics.getCounter("tool.calls.shell"));
        assertEquals(1L, metrics.getCounter("tool.errors.shell"));
    }

    @Test
    void metricsGetAllCounters() {
        OtelMetrics metrics = new OtelMetrics();
        metrics.increment("a", 1);
        metrics.increment("b", 2);
        Map<String, Long> all = metrics.getAllCounters();
        assertEquals(1L, all.get("a"));
        assertEquals(2L, all.get("b"));
    }

    @Test
    void configWithEndpoint() {
        OtelConfig config = OtelConfig.enabled("svc", "http://jaeger:4318/v1/traces");
        assertEquals("http://jaeger:4318/v1/traces", config.exporterEndpoint());
    }
}
