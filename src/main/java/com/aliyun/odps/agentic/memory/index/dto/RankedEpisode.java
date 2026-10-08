package com.aliyun.odps.agentic.memory.index.dto;

import java.util.List;

public record RankedEpisode(
    String sessionId,
    String userId,
    String workspaceId,
    long createdAt,
    long lastMessageAt,
    String goalText,
    String summaryText,
    List<String> toolNames,
    String status,
    double bm25Score
) {
    public RankedEpisode {
        if (toolNames == null) toolNames = List.of();
    }
}
