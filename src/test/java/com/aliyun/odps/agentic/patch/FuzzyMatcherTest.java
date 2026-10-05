package com.aliyun.odps.agentic.patch;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FuzzyMatcherTest {

    private final FuzzyMatcher matcher = new FuzzyMatcher();

    // ── Level 1: Exact match ──

    @Test
    void exactMatch_returnsCorrectIndex() {
        List<String> file = List.of("line1", "line2", "line3", "line4");
        List<String> old = List.of("line2", "line3");
        assertEquals(1, matcher.findMatch(file, old, null));
    }

    @Test
    void exactMatch_withStartHint() {
        List<String> file = List.of("a", "b", "a", "b");
        List<String> old = List.of("b");
        // With startHint=2, should find index 3 (second "b")
        assertEquals(3, matcher.findMatch(file, old, 2));
    }

    @Test
    void exactMatch_notFound_returnsMinusOne() {
        List<String> file = List.of("alpha", "beta");
        List<String> old = List.of("gamma");
        assertEquals(-1, matcher.findMatch(file, old, null));
    }

    // ── Level 2: Trim match ──

    @Test
    void trimMatch_whitespaceDifference() {
        List<String> file = List.of("  hello  ", "  world  ");
        List<String> old = List.of("hello", "world");
        assertEquals(0, matcher.findMatch(file, old, null));
    }

    @Test
    void trimMatch_partialWhitespace() {
        List<String> file = List.of("prefix", "  hello  ", "suffix");
        List<String> old = List.of("hello");
        assertEquals(1, matcher.findMatch(file, old, null));
    }

    // ── Level 3: Normalized indent match ──

    @Test
    void normalizedIndentMatch_tabVsSpace() {
        List<String> file = List.of("\thello", "\tworld");
        List<String> old = List.of("    hello", "    world");
        assertEquals(0, matcher.findMatch(file, old, null));
    }

    // ── Level 4: Levenshtein fuzzy match ──

    @Test
    void levenshteinMatch_minorTypo() {
        List<String> file = List.of("helo world");
        List<String> old = List.of("hello world");
        assertEquals(0, matcher.findMatch(file, old, null));
    }

    // ── Levenshtein distance unit tests ──

    @Test
    void levenshteinDistance_identical() {
        assertEquals(0, matcher.levenshteinDistance("hello", "hello"));
    }

    @Test
    void levenshteinDistance_empty() {
        assertEquals(5, matcher.levenshteinDistance("hello", ""));
        assertEquals(0, matcher.levenshteinDistance("", ""));
    }

    @Test
    void levenshteinDistance_insertion() {
        assertEquals(1, matcher.levenshteinDistance("abc", "abcd"));
    }

    @Test
    void levenshteinDistance_substitution() {
        assertEquals(1, matcher.levenshteinDistance("abc", "axc"));
    }

    // ── isSimilar ──

    @Test
    void isSimilar_withinThreshold() {
        assertTrue(matcher.isSimilar("hello", "helo"));
    }

    @Test
    void isSimilar_beyondThreshold() {
        assertFalse(matcher.isSimilar("hello", "xyzab"));
    }

    @Test
    void isSimilar_tooLong() {
        String a = "a".repeat(300);
        String b = "b".repeat(300);
        assertFalse(matcher.isSimilar(a, b)); // exceeds MAX_LEVENSHTEIN_LENGTH
    }

    @Test
    void isSimilar_emptyStrings() {
        assertTrue(matcher.isSimilar("", ""));
    }

    // ── Edge cases ──

    @Test
    void emptyOldLines_returnsZero() {
        List<String> file = List.of("a", "b", "c");
        assertEquals(0, matcher.findMatch(file, List.of(), null));
    }

    @Test
    void oldLinesLongerThanFile_returnsMinusOne() {
        List<String> file = List.of("a");
        List<String> old = List.of("a", "b", "c");
        assertEquals(-1, matcher.findMatch(file, old, null));
    }
}
