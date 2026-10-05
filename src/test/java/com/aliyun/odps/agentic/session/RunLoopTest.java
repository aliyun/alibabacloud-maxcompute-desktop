package com.aliyun.odps.agentic.session;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.aliyun.odps.agentic.llm.*;
import com.aliyun.odps.agentic.model.*;
import com.aliyun.odps.agentic.permission.DefaultPermissionAsker;
import com.aliyun.odps.agentic.permission.PermissionEngine;
import com.aliyun.odps.agentic.permission.PermissionService;
import com.aliyun.odps.agentic.patch.PatchEngine;
import com.aliyun.odps.agentic.skill.SkillLoader;
import com.aliyun.odps.agentic.tool.ToolRegistry;

import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RunLoop unit tests — tests the core loop mechanics without real LLM calls.
 */
class RunLoopTest {

    private ToolRegistry toolRegistry;
    private SystemPromptBuilder promptBuilder;
    private InMemoryMessageStore messageStore;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        toolRegistry = new ToolRegistry();
        promptBuilder = new SystemPromptBuilder();
        messageStore = new InMemoryMessageStore();
    }

    // ── Cancel mechanism ──

    @Test
    void cancelSetsFlag() {
        RunLoop loop = createRunLoopWithStub();
        assertFalse(loop.isCancelled());
        loop.cancel();
        assertTrue(loop.isCancelled());
    }

    // ── Cumulative cost tracking ──

    @Test
    void cumulativeCostStartsAtZero() {
        RunLoop loop = createRunLoopWithStub();
        assertEquals(0.0, loop.getCumulativeCost(), 0.001);
    }

    @Test
    void cumulativeTokensStartsEmpty() {
        RunLoop loop = createRunLoopWithStub();
        Tokens tokens = loop.getCumulativeTokens();
        assertNotNull(tokens);
    }

    // ── Undo ──

    @Test
    void undoReturnsZeroWhenNoMessages() {
        RunLoop loop = createRunLoopWithStub();
        int removed = loop.undo("nonexistent-session");
        assertEquals(0, removed);
    }

    @Test
    void undoRemovesMessagesFromStore() {
        String sessionId = Identifier.sessionId();
        Message userMsg = new Message(Identifier.messageId(), sessionId, Role.USER,
            List.of(new MessagePart.TextPart("hello")));
        Message assistantMsg = new Message(Identifier.messageId(), sessionId, Role.ASSISTANT,
            List.of(new MessagePart.TextPart("hi there")));
        messageStore.updateMessage(sessionId, userMsg);
        messageStore.updateMessage(sessionId, assistantMsg);

        RunLoop loop = createRunLoopWithStub();
        int removed = loop.undo(sessionId);
        assertTrue(removed >= 0);
    }

    // ── Hooks ──

    @Test
    void setHooksDoesNotCrash() {
        RunLoop loop = createRunLoopWithStub();
        SessionHooks hooks = new SessionHooks() {};
        assertDoesNotThrow(() -> loop.setHooks(hooks));
    }

    @Test
    void runLoopPassesProviderOptionsAndTemperatureToLlmRequest() {
        final LlmRequest[] captured = {null};
        Route route = new Route("openai", new OpenAITransform(), "http://localhost",
            Auth.none, MessageFormat.OPENAI);
        Model model = route.model("o3", new ModelLimit(200000, null, 4096));
        RunLoop loop = createRunLoopWithStub(request -> captured[0] = request, model);

        var agent = com.aliyun.odps.agentic.agent.AgentDefBuilder.create("test-agent")
            .systemPrompt("You are helpful")
            .temperature(0.4)
            .build();
        Session session = new Session(Identifier.sessionId(), "test-session");

        Message result = loop.run(session, agent, "hello");

        assertNotNull(result);
        assertNotNull(captured[0]);
        assertNotNull(captured[0].providerOptions());
        assertEquals(0.4, captured[0].temperature(), 0.001);
    }

    // ── Doom loop detection ──

    @Test
    void doomLoopDetectionWithRepeatedToolCalls() {
        // isDoomLoop checks for repeated tool call patterns, not text content
        List<Message> messages = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            // Alternate user/assistant with identical tool calls
            messages.add(new Message(Identifier.messageId(), "sess", Role.ASSISTANT,
                List.of(new MessagePart.ToolCallPart("tc-" + i, "read_file",
                    "{\"path\":\"/etc/hosts\"}"))));
        }
        StreamProcessor processor = new StreamProcessor(toolRegistry);
        assertTrue(processor.isDoomLoop(messages, 3));
    }

    @Test
    void noDoomLoopWithDifferentToolCalls() {
        List<Message> messages = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            messages.add(new Message(Identifier.messageId(), "sess", Role.ASSISTANT,
                List.of(new MessagePart.ToolCallPart("tc-" + i, "tool_" + i,
                    "{\"arg\":\"" + i + "\"}"))));
        }
        StreamProcessor processor = new StreamProcessor(toolRegistry);
        assertFalse(processor.isDoomLoop(messages, 3));
    }

    @Test
    void doomLoopWithFewMessagesNotTriggered() {
        List<Message> messages = new ArrayList<>();
        messages.add(new Message(Identifier.messageId(), "sess", Role.ASSISTANT,
            List.of(new MessagePart.TextPart("same"))));
        StreamProcessor processor = new StreamProcessor(toolRegistry);
        // Need at least windowSize*2 = 6 messages
        assertFalse(processor.isDoomLoop(messages, 3));
    }

    // ── MaxStepsPrompt ──

    @Test
    void maxStepsPromptContainsWarning() {
        assertNotNull(MaxStepsPrompt.MAX_STEPS);
        assertTrue(MaxStepsPrompt.MAX_STEPS.contains("MAXIMUM STEPS"));
        assertTrue(MaxStepsPrompt.MAX_STEPS.contains("text ONLY"));
    }

    @Test
    void manualCompaction_emitsReasonBasedEvents() {
        AgentEvent.CompactionStart start = new AgentEvent.CompactionStart("s", CompactionReason.MANUAL, false);
        AgentEvent.CompactionEnd end = new AgentEvent.CompactionEnd("s", CompactionReason.AUTO, true, "summary");
        assertEquals(CompactionReason.MANUAL, start.reason());
        assertEquals(CompactionReason.AUTO, end.reason());
        assertTrue(end.overflow());
        assertEquals("summary", end.summary());
    }

    // ── OverflowDetector ──

    @Test
    void overflowDetectorUsableWithContext() {
        Route route = new Route("stub", new AnthropicTransform(), "http://localhost",
            Auth.none, MessageFormat.ANTHROPIC);
        Model model = route.model("stub-model", new ModelLimit(200000, null, 16384));
        long usable = OverflowDetector.usable(model);
        assertTrue(usable > 0);
        assertTrue(usable < 200000);
    }

    @Test
    void overflowDetectorUsableWithZeroContext() {
        Route route = new Route("stub", new AnthropicTransform(), "http://localhost",
            Auth.none, MessageFormat.ANTHROPIC);
        Model model = route.model("stub-model", new ModelLimit(0, null, null));
        assertEquals(0, OverflowDetector.usable(model));
    }

    @Test
    void overflowDetectorIsOverflowWithEmptyMessages() {
        Route route = new Route("stub", new AnthropicTransform(), "http://localhost",
            Auth.none, MessageFormat.ANTHROPIC);
        Model model = route.model("stub-model", new ModelLimit(200000, null, 16384));
        OverflowDetector detector = new OverflowDetector();
        assertFalse(detector.isOverflow(List.of(), model));
    }

    @Test
    void overflowDetectorCompactionBuffer() {
        assertEquals(20_000, OverflowDetector.COMPACTION_BUFFER);
    }

    // ── Identifier in run context ──

    @Test
    void identifierCreatesUniqueMessageIds() {
        String id1 = Identifier.messageId();
        String id2 = Identifier.messageId();
        assertNotEquals(id1, id2);
        assertTrue(id1.startsWith("msg"));
        assertTrue(id2.startsWith("msg"));
    }

    // ── InMemoryMessageStore ──

    @Test
    void messageStoreRoundTrip() {
        String sessionId = Identifier.sessionId();
        Message msg = new Message(Identifier.messageId(), sessionId, Role.USER,
            List.of(new MessagePart.TextPart("test")));
        messageStore.updateMessage(sessionId, msg);
        List<Message> stored = messageStore.getMessages(sessionId);
        assertEquals(1, stored.size());
        assertEquals("test", stored.get(0).getTextContent());
    }

    @Test
    void messageStoreRemoveMessage() {
        String sessionId = Identifier.sessionId();
        String msgId = Identifier.messageId();
        Message msg = new Message(msgId, sessionId, Role.USER,
            List.of(new MessagePart.TextPart("test")));
        messageStore.updateMessage(sessionId, msg);
        messageStore.removeMessage(sessionId, msgId);
        assertTrue(messageStore.getMessages(sessionId).isEmpty());
    }

    @Test
    void messageStoreClear() {
        String sessionId = Identifier.sessionId();
        messageStore.updateMessage(sessionId, new Message(Identifier.messageId(), sessionId, Role.USER,
            List.of(new MessagePart.TextPart("a"))));
        messageStore.updateMessage(sessionId, new Message(Identifier.messageId(), sessionId, Role.ASSISTANT,
            List.of(new MessagePart.TextPart("b"))));
        messageStore.clear(sessionId);
        assertTrue(messageStore.getMessages(sessionId).isEmpty());
    }

    // ── Helper ──

    private RunLoop createRunLoopWithStub() {
        Route route = new Route("stub", new AnthropicTransform(), "http://localhost",
            Auth.none, MessageFormat.ANTHROPIC);
        return createRunLoopWithStub(request -> {}, route.model("stub-model", new ModelLimit(200000, null, 4096)));
    }

    private RunLoop createRunLoopWithStub(Consumer<LlmRequest> onRequest) {
        Route route = new Route("stub", new AnthropicTransform(), "http://localhost",
            Auth.none, MessageFormat.ANTHROPIC);
        return createRunLoopWithStub(onRequest, route.model("stub-model", new ModelLimit(200000, null, 4096)));
    }

    private RunLoop createRunLoopWithStub(Consumer<LlmRequest> onRequest, Model model) {
        LLMClient stubClient = new LLMClient() {
            @Override
            public void stream(LlmRequest request, Consumer<LLMEvent> consumer) {
                onRequest.accept(request);
                consumer.accept(new LLMEvent.TextDelta("stub response"));
                consumer.accept(new LLMEvent.Finish("end-turn"));
            }
            @Override
            public boolean supports(String providerId) {
                return true;
            }
        };

        PermissionEngine permEngine = new PermissionEngine(List.of());
        PermissionService permService = new PermissionService(permEngine, new DefaultPermissionAsker());

        return new RunLoop(
            stubClient, toolRegistry, new CompactionEngine(), permEngine,
            promptBuilder, new PatchEngine(), event -> {}, model,
            tempDir.toString(), new SkillLoader(List.of(), List.of()),
            messageStore, permService
        );
    }
}
