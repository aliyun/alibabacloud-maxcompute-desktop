package com.aliyun.odps.agentic.memory.index.dto;

public record RebuildResult(int totalScanned, int upserted, int failed, long durationMs) {
}
