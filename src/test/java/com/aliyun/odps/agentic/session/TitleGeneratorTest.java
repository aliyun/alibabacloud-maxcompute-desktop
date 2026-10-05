package com.aliyun.odps.agentic.session;

import com.aliyun.odps.agentic.llm.LLMClient;
import com.aliyun.odps.agentic.llm.LLMEvent;
import com.aliyun.odps.agentic.llm.LlmRequest;
import com.aliyun.odps.agentic.llm.Model;
import com.aliyun.odps.agentic.llm.ModelLimit;
import org.junit.jupiter.api.Test;

import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for TitleGenerator — LLM-based title generation, cleanTitle, fallbackTitle.
 */
class TitleGeneratorTest {

    // -- cleanTitle --

    @Test
    void cleanTitleNull() {
        assertEquals("", TitleGenerator.cleanTitle(null));
    }

    @Test
    void cleanTitleEmpty() {
        assertEquals("", TitleGenerator.cleanTitle(""));
    }

    @Test
    void cleanTitleStripsDoubleQuotes() {
        assertEquals("Hello World", TitleGenerator.cleanTitle("\"Hello World\""));
    }

    @Test
    void cleanTitleStripsSingleQuotes() {
        assertEquals("Hello World", TitleGenerator.cleanTitle("'Hello World'"));
    }

    @Test
    void cleanTitleRemovesTrailingPunctuation() {
        assertEquals("Hello World", TitleGenerator.cleanTitle("Hello World..."));
        assertEquals("Question", TitleGenerator.cleanTitle("Question?"));
        assertEquals("Exclaim", TitleGenerator.cleanTitle("Exclaim!"));
    }

    @Test
    void cleanTitleCombinedQuotesAndPunctuation() {
        assertEquals("My Title", TitleGenerator.cleanTitle("\"My Title.\""));
    }

    @Test
    void cleanTitleTrimsWhitespace() {
        assertEquals("Spaced", TitleGenerator.cleanTitle("  Spaced  "));
    }

    // -- fallbackTitle --

    @Test
    void fallbackTitleNull() {
        assertEquals("New Session", TitleGenerator.fallbackTitle(null));
    }

    @Test
    void fallbackTitleBlank() {
        assertEquals("New Session", TitleGenerator.fallbackTitle("   "));
    }

    @Test
    void fallbackTitleSingleLine() {
        assertEquals("Simple message", TitleGenerator.fallbackTitle("Simple message"));
    }

    @Test
    void fallbackTitleMultilineTakesFirst() {
        assertEquals("First line", TitleGenerator.fallbackTitle("First line\nSecond line\nThird"));
    }

    @Test
    void fallbackTitleLongLineTruncated() {
        String longLine = "A".repeat(100);
        String result = TitleGenerator.fallbackTitle(longLine);
        assertEquals(80, result.length());
        assertTrue(result.endsWith("..."));
        assertEquals("A".repeat(77) + "...", result);
    }

    @Test
    void fallbackTitleExactly80Chars() {
        String line80 = "B".repeat(80);
        assertEquals(line80, TitleGenerator.fallbackTitle(line80));
    }

    // -- generateTitle --

    @Test
    void generateTitleNullInput() {
        LLMClient stub = stubClient("Ignored");
        Model model = Model.of("test", "test-model", new ModelLimit(100000, null, 4096));
        assertEquals("New Session", TitleGenerator.generateTitle(null, stub, model));
    }

    @Test
    void generateTitleBlankInput() {
        LLMClient stub = stubClient("Ignored");
        Model model = Model.of("test", "test-model", new ModelLimit(100000, null, 4096));
        assertEquals("New Session", TitleGenerator.generateTitle("   ", stub, model));
    }

    @Test
    void generateTitleFromLLM() {
        LLMClient stub = stubClient("Setup Docker Environment");
        Model model = Model.of("test", "test-model", new ModelLimit(100000, null, 4096));
        assertEquals("Setup Docker Environment", TitleGenerator.generateTitle("Help me set up Docker", stub, model));
    }

    @Test
    void generateTitleLLMReturnsQuoted() {
        LLMClient stub = stubClient("\"Quoted Title\"");
        Model model = Model.of("test", "test-model", new ModelLimit(100000, null, 4096));
        assertEquals("Quoted Title", TitleGenerator.generateTitle("some input", stub, model));
    }

    @Test
    void generateTitleLLMFailsFallsBack() {
        LLMClient failingClient = new LLMClient() {
            @Override
            public void stream(LlmRequest request, Consumer<LLMEvent> eventConsumer) {
                throw new RuntimeException("LLM unavailable");
            }
            @Override
            public boolean supports(String providerId) { return true; }
        };
        Model model = Model.of("test", "test-model", new ModelLimit(100000, null, 4096));
        assertEquals("Help me with something", TitleGenerator.generateTitle("Help me with something", failingClient, model));
    }

    @Test
    void generateTitleLLMReturnsEmpty() {
        LLMClient stub = stubClient("");
        Model model = Model.of("test", "test-model", new ModelLimit(100000, null, 4096));
        String result = TitleGenerator.generateTitle("My input message", stub, model);
        assertEquals("My input message", result);
    }

    // -- Helpers --

    private static LLMClient stubClient(String titleText) {
        return new LLMClient() {
            @Override
            public void stream(LlmRequest request, Consumer<LLMEvent> eventConsumer) {
                eventConsumer.accept(new LLMEvent.TextDelta(titleText));
                eventConsumer.accept(new LLMEvent.Finish("end-turn"));
            }
            @Override
            public boolean supports(String providerId) { return true; }
        };
    }
}
