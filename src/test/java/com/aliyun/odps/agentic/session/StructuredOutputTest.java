package com.aliyun.odps.agentic.session;

import com.aliyun.odps.agentic.HarnessEngine;
import com.aliyun.odps.agentic.agent.AgentDef;
import com.aliyun.odps.agentic.llm.LLMClient;
import com.aliyun.odps.agentic.llm.LLMEvent;
import com.aliyun.odps.agentic.llm.LlmRequest;
import com.aliyun.odps.agentic.llm.Model;
import com.aliyun.odps.agentic.llm.ModelLimit;
import com.aliyun.odps.agentic.model.Message;
import com.aliyun.odps.agentic.model.Session;
import com.aliyun.odps.agentic.model.SessionStatus;
import com.aliyun.odps.agentic.tool.ToolDef;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 类型安全结构化输出（0.4.0 / P1-G）的回归测试。
 *
 * <p>取代了「在用户文本里嗅探 json_schema 子串」的脆弱做法。
 */
@Timeout(20)
class StructuredOutputTest {

    /** 目标输出类型（record）。 */
    record Person(String name, int age) {}

    private AgentDef testAgent() {
        return new AgentDef() {
            @Override public String getName() { return "test"; }
            @Override public String getSystemPrompt(Function<String, String> mp) { return "You output structured data."; }
            @Override public List<ToolDef> getTools() { return List.of(); }
            @Override public int getMaxSteps() { return 5; }
        };
    }

    private Session titledSession(AgentDef agent) {
        Instant now = Instant.now();
        return new Session(
            UUID.randomUUID().toString(), "t", SessionStatus.IDLE, List.of(),
            agent.getName(), Model.of("test", "test-model", new ModelLimit(100000, null, 4096)),
            0.0, null, now, now, agent.getPermissionRules()
        );
    }

    @Test
    void runStructuredParsesToTypedPojo() {
        // 模型第一轮就调用 StructuredOutput 工具提交数据，然后结束。
        LLMClient llm = new LLMClient() {
            @Override public void stream(LlmRequest req, Consumer<LLMEvent> c) {
                boolean alreadyCalledTool = req.messages().toString().contains("Structured output captured");
                if (!alreadyCalledTool) {
                    c.accept(new LLMEvent.ToolCall("so1", "StructuredOutput",
                        "{\"output\":{\"name\":\"Alice\",\"age\":30}}"));
                    c.accept(new LLMEvent.Finish("tool-calls"));
                } else {
                    c.accept(new LLMEvent.TextDelta("done"));
                    c.accept(new LLMEvent.Finish("end-turn"));
                }
            }
            @Override public boolean supports(String p) { return true; }
        };

        HarnessEngine engine = HarnessEngine.builder()
            .workDir(Path.of("."))
            .llmClient(llm)
            .model(Model.of("test", "test-model", new ModelLimit(100000, null, 4096)))
            .build();
        try {
            AgentDef agent = testAgent();
            Session session = titledSession(agent);

            HarnessEngine.StructuredResult<Person> result =
                engine.runStructured(session, agent, "give me a person", Person.class);

            assertTrue(result.hasOutput(), "structured output should have been submitted");
            assertNotNull(result.output());
            assertEquals("Alice", result.output().name());
            assertEquals(30, result.output().age());
        } finally {
            engine.shutdown();
        }
    }

    @Test
    void runStructuredWithoutSubmissionReturnsNoOutput() {
        // 模型从不调用结构化输出工具 → hasOutput=false，output=null（不抛错）。
        LLMClient llm = new LLMClient() {
            @Override public void stream(LlmRequest req, Consumer<LLMEvent> c) {
                c.accept(new LLMEvent.TextDelta("plain answer"));
                c.accept(new LLMEvent.Finish("end-turn"));
            }
            @Override public boolean supports(String p) { return true; }
        };

        HarnessEngine engine = HarnessEngine.builder()
            .workDir(Path.of("."))
            .llmClient(llm)
            .model(Model.of("test", "test-model", new ModelLimit(100000, null, 4096)))
            .build();
        try {
            AgentDef agent = testAgent();
            Session session = titledSession(agent);
            HarnessEngine.StructuredResult<Person> result =
                engine.runStructured(session, agent, "just chat", Person.class);

            assertFalse(result.hasOutput());
            assertNull(result.output());
            assertEquals("plain answer", result.message().getTextContent());
        } finally {
            engine.shutdown();
        }
    }

    @Test
    void schemaGeneratorCoversCommonShapes() {
        var schema = com.aliyun.odps.agentic.llm.JsonSchemaGenerator.schemaFor(Person.class);
        assertEquals("object", schema.get("type").asText());
        assertEquals("string", schema.get("properties").get("name").get("type").asText());
        assertEquals("integer", schema.get("properties").get("age").get("type").asText());
        assertTrue(schema.get("required").toString().contains("name"));
        assertTrue(schema.get("required").toString().contains("age"));
    }
}
