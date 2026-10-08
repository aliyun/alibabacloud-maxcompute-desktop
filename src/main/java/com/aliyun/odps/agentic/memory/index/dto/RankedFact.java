package com.aliyun.odps.agentic.memory.index.dto;

import com.aliyun.odps.agentic.memory.index.SemanticFact;

public record RankedFact(SemanticFact fact, double bm25Score) {
}
