package com.aliyun.odps.agentic.session;

import com.aliyun.odps.agentic.agent.AgentDef;
import com.aliyun.odps.agentic.llm.*;
import com.aliyun.odps.agentic.model.*;
import com.aliyun.odps.agentic.patch.PatchEngine;
import com.aliyun.odps.agentic.permission.PermissionEngine;
import com.aliyun.odps.agentic.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class RunLoopContinuationTest {
    @TempDir Path tempDir;
    private final Model model = Model.of("test", "model", new ModelLimit(100000, null, 4096));

    @Test
    void coldResumeProcessesNewSystemFeedbackOnceAndReusesCompletedAnswersOtherwise() {
        String path = tempDir.resolve("messages.db").toString();
        MessageStore store = new SQLiteMessageStore(path);
        AtomicInteger calls = new AtomicInteger();
        LLMClient client = client(calls, request -> {
            if (calls.get() == 2) {
                assertTrue(request.sourceMessages().stream().anyMatch(message ->
                    message.role() == Role.SYSTEM && "补充读取范围".equals(message.getTextContent())));
                assertEquals(1, request.sourceMessages().stream().filter(message ->
                    message.role() == Role.USER && "读取正文".equals(message.getTextContent())).count());
            }
        });
        Session session = session();
        AgentDef agent = agent((s, definition, candidate, history) -> RunPolicy.FinishDecision.accept());
        Message initial = loop(client, store).run(session, agent, "读取正文");
        assertEquals("end-turn", initial.finish());
        assertEquals("reply-1", initial.getTextContent());

        assertEquals(initial, loop(client, new SQLiteMessageStore(path)).resumeUntilPause(session, agent).message());
        assertEquals(1, calls.get(), "resuming completed history without feedback must not call the provider");
        store.updateMessage(session.id(), new Message("feedback", session.id(), Role.SYSTEM,
            List.of(new MessagePart.TextPart("补充读取范围"))));
        Message revised = loop(client, new SQLiteMessageStore(path)).resumeUntilPause(session, agent).message();
        assertEquals("reply-2", revised.getTextContent());
        assertEquals(2, calls.get());
        MessageStore reopened = new SQLiteMessageStore(path);
        assertEquals(List.of(Role.USER, Role.ASSISTANT, Role.SYSTEM, Role.ASSISTANT),
            reopened.getMessages(session.id()).stream().map(Message::role).toList());
        assertEquals(revised, loop(client, reopened).resumeUntilPause(session, agent).message());
        assertEquals(2, calls.get(), "already consumed feedback must not reopen the completed turn");
    }

    @Test
    void systemInstructionArrivingAtFinishBoundaryIsProcessedBeforeExit() {
        MessageStore store = new InMemoryMessageStore();
        AtomicInteger calls = new AtomicInteger();
        LLMClient client = client(calls, request -> {
            if (calls.get() == 2) assertTrue(request.sourceMessages().stream()
                .anyMatch(message -> "晚到的反馈".equals(message.getTextContent())));
        });
        RunPolicy policy = (session, definition, candidate, history) -> {
            if (calls.get() == 1) store.updateMessage(session.id(), new Message("late-feedback", session.id(),
                Role.SYSTEM, List.of(new MessagePart.TextPart("晚到的反馈"))));
            return RunPolicy.FinishDecision.accept();
        };
        Message result = loop(client, store).run(session(), agent(policy), "读取正文");
        assertEquals("reply-2", result.getTextContent());
        assertEquals(2, calls.get());
    }

    private LLMClient client(AtomicInteger calls, Consumer<LlmRequest> verify) {
        return new LLMClient() {
            @Override public void stream(LlmRequest request, Consumer<LLMEvent> events) {
                int call = calls.incrementAndGet();
                verify.accept(request);
                events.accept(new LLMEvent.TextDelta("reply-" + call));
                events.accept(new LLMEvent.Finish("end-turn"));
            }
            @Override public boolean supports(String providerId) { return true; }
            @Override public boolean requiresProviderProjection() { return false; }
        };
    }

    private AgentDef agent(RunPolicy policy) {
        return new AgentDef() {
            @Override public String getName() { return "test"; }
            @Override public String getSystemPrompt(Function<String, String> modelProvider) { return "Test"; }
            @Override public RunPolicy getRunPolicy() { return policy; }
        };
    }

    private Session session() {
        Instant now = Instant.now();
        return new Session("continue", "Continue", SessionStatus.IDLE, List.of(), "test", model,
            0.0, null, now, now, List.of());
    }

    private RunLoop loop(LLMClient client, MessageStore store) {
        return new RunLoop(client, new ToolRegistry(), new CompactionEngine(), new PermissionEngine(List.of()),
            new SystemPromptBuilder(), new PatchEngine(), event -> {}, model, ".", null, store, null);
    }
}
