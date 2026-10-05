package com.aliyun.odps.agentic.llm;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ChunkedTextSummaryTest {
    @Test
    void processesEverySourceCharacterAndCarriesThePreviousSummaryWithinBudget() {
        String source = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
        List<ChunkedTextSummary.Chunk> chunks = new ArrayList<>();
        var responses = ChunkedTextSummary.run(source, 16, chunk -> {
            assertEquals(chunks.isEmpty() ? "" : "summary" + chunks.size(), chunk.previousSummary());
            assertTrue(chunk.previousSummary().length() + chunk.text().length() <= 16);
            chunks.add(chunk);
            return "summary" + chunks.size();
        }, value -> value);
        assertTrue(responses.size() > 2);
        assertEquals(source, chunks.stream().map(ChunkedTextSummary.Chunk::text)
            .reduce("", String::concat));
    }

    @Test
    void preservesSurrogatePairsAtChunkBoundaries() {
        String source = "abcd🙂efgh🙂ij";
        List<String> chunks = new ArrayList<>();
        ChunkedTextSummary.run(source, 5, chunk -> {
            assertFalse(Character.isHighSurrogate(chunk.text().charAt(chunk.text().length() - 1)));
            assertFalse(Character.isLowSurrogate(chunk.text().charAt(0)));
            chunks.add(chunk.text());
            return "x";
        }, value -> value);
        assertEquals(source, String.join("", chunks));
    }

    @Test
    void stopsOnProviderFailureWithoutSummarizingLaterChunks() {
        AtomicInteger calls = new AtomicInteger();
        RuntimeException failure = new RuntimeException("provider unavailable");
        assertSame(failure, assertThrows(RuntimeException.class,
            () -> ChunkedTextSummary.run("x".repeat(100), 10, chunk -> {
                if (calls.incrementAndGet() == 2) throw failure;
                return "summary";
            }, value -> value)));
        assertEquals(2, calls.get());
    }

    @Test
    void cannotSilentlyProceedAfterAnEmptyIntermediateSummary() {
        AtomicInteger calls = new AtomicInteger();
        assertThrows(IllegalStateException.class, () -> ChunkedTextSummary.run("x".repeat(100), 10,
            chunk -> { calls.incrementAndGet(); return ""; }, value -> value));
        assertEquals(1, calls.get());
    }

    @Test
    void emptyInputStillMakesOneRequestAndOversizedSummaryFailsWithoutTruncation() {
        assertEquals(List.of("done"), ChunkedTextSummary.run("", 10, chunk -> "done", value -> value));
        AtomicInteger calls = new AtomicInteger();
        assertThrows(IllegalStateException.class, () -> ChunkedTextSummary.run("x".repeat(100), 10,
            chunk -> { calls.incrementAndGet(); return "too large a summary"; }, value -> value));
        assertEquals(1, calls.get());
    }
}
