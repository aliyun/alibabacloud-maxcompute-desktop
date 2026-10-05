package com.aliyun.odps.agentic.llm;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.function.Function;

/** Runs a rolling summary without omitting source text or exceeding a request's text budget. */
public final class ChunkedTextSummary {
    private ChunkedTextSummary() {}

    public record Chunk(String previousSummary, String text) {}

    /** The host reserves room for its prompt/framing before supplying maxTextChars. */
    public static <T> List<T> run(String source, int maxTextChars,
                                  Function<Chunk, T> summarize, Function<T, String> textOf) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(summarize, "summarize");
        Objects.requireNonNull(textOf, "textOf");
        if (maxTextChars < 2) throw new IllegalArgumentException("Text budget must be at least two chars");
        List<T> responses = new ArrayList<>();
        String summary = "";
        int offset = 0;
        do {
            if (Thread.currentThread().isInterrupted()) throw new CancellationException("Summary interrupted");
            int capacity = maxTextChars - summary.length();
            if (capacity < 2) throw new IllegalStateException("Previous summary exhausted the text budget");
            int end = offset + Math.min(capacity, source.length() - offset);
            if (end < source.length() && end > offset && Character.isHighSurrogate(source.charAt(end - 1))
                    && Character.isLowSurrogate(source.charAt(end))) end--;
            T response = summarize.apply(new Chunk(summary, source.substring(offset, end)));
            responses.add(response);
            summary = Objects.requireNonNullElse(textOf.apply(response), "");
            offset = end;
            if (offset < source.length() && summary.isBlank()) {
                throw new IllegalStateException("An intermediate summary was empty");
            }
        } while (offset < source.length());
        return List.copyOf(responses);
    }
}
