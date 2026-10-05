package com.aliyun.odps.agentic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import com.aliyun.odps.agentic.agent.AgentDef;
import com.aliyun.odps.agentic.agent.AgentDefBuilder;
import com.aliyun.odps.agentic.llm.LLMClient;
import com.aliyun.odps.agentic.llm.LLMEvent;
import com.aliyun.odps.agentic.llm.LlmRequest;
import com.aliyun.odps.agentic.llm.Model;
import com.aliyun.odps.agentic.llm.ModelLimit;
import com.aliyun.odps.agentic.model.Message;
import com.aliyun.odps.agentic.model.Role;
import com.aliyun.odps.agentic.model.Session;
import com.aliyun.odps.agentic.session.AgentEvent;
import com.aliyun.odps.agentic.tool.ToolContext;
import com.aliyun.odps.agentic.tool.ToolDef;
import com.aliyun.odps.agentic.tool.ToolResult;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Agent 最小 Demo —— 纯本地、零外部依赖的 E2E 学习用例。
 *
 * <p>这组测试展示了 Harness SDK 的核心流程：
 * <ol>
 *   <li>用 Mock LLM 替代真实 API，让测试离线可运行</li>
 *   <li>通过 {@link AgentDefBuilder} 构建 Agent 定义</li>
 *   <li>通过 {@link HarnessEngine} 驱动完整的 Agent 循环</li>
 * </ol>
 *
 * <p>直接运行：{@code mvn test -Dtest=AgentMinimalDemoTest}
 *
 * <h2>SDK 核心架构概念</h2>
 * <pre>
 *  用户代码
 *     │
 *     ▼
 *  HarnessEngine          ← 固定运行时引擎
 *     │
 *     ├─ AgentDef          ← 可插拔的 Agent 定义（名称、提示词、工具、权限）
 *     ├─ LLMClient         ← LLM 通信层（本 Demo 用 Mock 实现）
 *     ├─ ToolRegistry      ← 工具注册中心
 *     └─ RunLoop           ← 核心循环：调 LLM → 解析响应 → 执行工具 → 再调 LLM → …
 *
 *  一次完整的 Agent 运行流程：
 *  ┌─────────┐    ┌─────────┐    ┌──────────┐    ┌─────────┐
 *  │ 用户消息 │──▶│  LLM    │──▶│ 工具调用  │──▶│  LLM    │──▶ 最终文本响应
 *  └─────────┘    └─────────┘    └──────────┘    └─────────┘
 * </pre>
 */
class AgentMinimalDemoTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * SDK 内部会异步调用 LLM 生成会话标题（TitleGenerator），
     * 该请求的 system prompt 包含此前缀。Mock LLM 需要识别并跳过它，
     * 避免干扰主流程的步骤计数。
     */
    private static boolean isTitleRequest(LlmRequest request) {
        return request.system() != null
            && request.system().stream().anyMatch(s -> s.contains("Generate a concise title"));
    }

    private static void handleTitleRequest(Consumer<LLMEvent> events) {
        events.accept(new LLMEvent.TextDelta("Demo Title"));
        events.accept(new LLMEvent.Finish("end-turn"));
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // Demo 1: 最简 Agent —— 一问一答，无工具
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * 最简单的 Agent 场景：用户提问，LLM 直接回答，没有工具调用。
     *
     * <p>学习要点：
     * <ul>
     *   <li>如何创建 Mock LLMClient（发射 SSE 事件序列）</li>
     *   <li>如何通过 Builder 构建 Engine 和 Agent</li>
     *   <li>engine.run() 返回的 Message 结构</li>
     * </ul>
     */
    @Test
    @Timeout(10)
    void demo1_simpleQA() {
        // ── 第 1 步：创建 Mock LLM ──
        // LLMClient 接口只有一个 stream() 方法，通过 Consumer<LLMEvent> 发射事件。
        // 真实场景下会解析 SSE 流，这里直接手动发射事件来模拟 LLM 响应。
        LLMClient mockLlm = new LLMClient() {
            @Override
            public void stream(LlmRequest request, Consumer<LLMEvent> events) {
                // TextDelta: LLM 生成的文本片段（流式输出的核心）
                events.accept(new LLMEvent.TextDelta("Java 是 1995 年由 Sun Microsystems 发布的。"));
                // Finish: 标记生成结束，"end-turn" 表示正常完成
                events.accept(new LLMEvent.Finish("end-turn"));
            }

            @Override
            public boolean supports(String providerId) { return true; }
        };

        // ── 第 2 步：创建 Model（描述 LLM 的能力边界）──
        // Model 绑定了 provider + model ID + token 限制
        Model model = Model.of("mock", "mock-model", new ModelLimit(128000, null, 4096));

        // ── 第 3 步：构建 HarnessEngine（固定运行时）──
        HarnessEngine engine = HarnessEngine.builder()
            .llmClient(mockLlm)
            .model(model)
            .build();

        // ── 第 4 步：定义 Agent（可插拔的行为描述）──
        // AgentDefBuilder 是最快捷的方式，只需名称 + 系统提示词
        AgentDef agent = AgentDefBuilder.create("qa-agent")
            .systemPrompt("你是一个知识问答助手。用中文简洁回答。")
            .maxSteps(5)
            .build();

        // ── 第 5 步：创建 Session 并运行 ──
        // Session 是一次对话的状态容器（消息历史、token 消耗等）
        Session session = engine.createSession(agent);
        Message result = engine.run(session, agent, "Java 是哪年发布的？");

        // ── 验证 ──
        assertNotNull(result);
        assertEquals(Role.ASSISTANT, result.role());
        assertTrue(result.getTextContent().contains("1995"));

        engine.shutdown();
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // Demo 2: Agent + 自定义工具 —— LLM 调用工具后再回答
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * 核心场景：Agent 调用自定义工具获取数据，然后基于结果生成回答。
     *
     * <p>这是 Agent 区别于普通聊天机器人的关键能力 —— 工具调用（Tool Use）。
     *
     * <p>完整流程：
     * <pre>
     *  用户: "北京今天天气怎么样？"
     *     ↓
     *  LLM 第 1 轮: 决定调用 get_weather 工具，参数 {"city": "北京"}
     *     ↓
     *  RunLoop: 执行工具 → 得到 "晴，25°C"
     *     ↓
     *  LLM 第 2 轮: 基于工具结果生成最终回答 "北京今天晴，气温 25°C"
     * </pre>
     *
     * <p>学习要点：
     * <ul>
     *   <li>如何实现 {@link ToolDef} 接口（getId / getDescription / getParametersSchema / execute）</li>
     *   <li>Mock LLM 如何模拟「先调工具、再回答」的两步流程</li>
     *   <li>工具结果如何被传回 LLM</li>
     * </ul>
     */
    @Test
    @Timeout(10)
    void demo2_agentWithToolCall() {
        // ── 自定义工具：天气查询 ──
        WeatherTool weatherTool = new WeatherTool();

        // ── Mock LLM：根据轮次模拟不同行为 ──
        // 注意：SDK 内部会异步调用 TitleGenerator 生成标题，也走同一个 LLMClient，
        // 所以必须用 isTitleRequest() 过滤掉标题请求，避免 step 计数被干扰。
        int[] step = {0};
        LLMClient mockLlm = new LLMClient() {
            @Override
            public void stream(LlmRequest request, Consumer<LLMEvent> events) {
                if (isTitleRequest(request)) { handleTitleRequest(events); return; }

                step[0]++;
                if (step[0] == 1) {
                    // 第 1 轮：LLM 决定调用工具
                    // ToolCall(callId, 工具名, 参数JSON) —— 三参数版本直接传入完整参数
                    events.accept(new LLMEvent.ToolCall("call_1", "get_weather", "{\"city\":\"北京\"}"));
                    // finish reason = "tool-use" 告诉 RunLoop：需要执行工具，不是最终回答
                    events.accept(new LLMEvent.Finish("tool-use"));
                } else {
                    // 第 2 轮：LLM 基于工具结果生成最终回答
                    // 此时 request.messages() 中已包含工具执行结果
                    events.accept(new LLMEvent.TextDelta("北京今天晴，气温 25°C，适合出行。"));
                    events.accept(new LLMEvent.Finish("end-turn"));
                }
            }

            @Override
            public boolean supports(String providerId) { return true; }
        };

        Model model = Model.of("mock", "mock-model", new ModelLimit(128000, null, 4096));

        HarnessEngine engine = HarnessEngine.builder()
            .llmClient(mockLlm)
            .model(model)
            .build();

        // Agent 通过 addTool() 注入自定义工具
        AgentDef agent = AgentDefBuilder.create("weather-agent")
            .systemPrompt("你是天气助手。用 get_weather 工具查询天气后回答用户。")
            .addTool(weatherTool)
            .maxSteps(5)
            .build();

        Session session = engine.createSession(agent);
        Message result = engine.run(session, agent, "北京今天天气怎么样？");

        // ── 验证 ──
        assertNotNull(result);
        assertEquals(Role.ASSISTANT, result.role());
        assertTrue(result.getTextContent().contains("25°C"));
        assertTrue(weatherTool.wasCalled, "工具应该被调用过");
        assertEquals(2, step[0], "LLM 应该被调用 2 次（工具调用 + 最终回答）");

        engine.shutdown();
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // Demo 3: 流式事件监听 —— 实时观察 Agent 执行过程
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * 展示如何通过 runStreaming() 实时监听 Agent 的执行事件。
     *
     * <p>在真实 UI 中，这些事件用于驱动：
     * <ul>
     *   <li>打字机效果（TextDelta 事件）</li>
     *   <li>工具调用状态指示（ToolCallStarted/Completed）</li>
     *   <li>步骤进度条（StepStart/StepFinish）</li>
     * </ul>
     *
     * <p>学习要点：
     * <ul>
     *   <li>{@link AgentEvent} 的事件类型体系（sealed interface + 24 种事件）</li>
     *   <li>事件的生命周期顺序</li>
     * </ul>
     */
    @Test
    @Timeout(10)
    void demo3_streamingEvents() {
        int[] step = {0};
        LLMClient mockLlm = new LLMClient() {
            @Override
            public void stream(LlmRequest request, Consumer<LLMEvent> events) {
                if (isTitleRequest(request)) { handleTitleRequest(events); return; }

                step[0]++;
                if (step[0] == 1) {
                    events.accept(new LLMEvent.ToolCall("call_1", "get_weather", "{\"city\":\"上海\"}"));
                    events.accept(new LLMEvent.Finish("tool-use"));
                } else {
                    // 多个 TextDelta 模拟流式打字效果
                    events.accept(new LLMEvent.TextDelta("上海"));
                    events.accept(new LLMEvent.TextDelta("今天"));
                    events.accept(new LLMEvent.TextDelta("多云，22°C。"));
                    events.accept(new LLMEvent.Finish("end-turn"));
                }
            }

            @Override
            public boolean supports(String providerId) { return true; }
        };

        Model model = Model.of("mock", "mock-model", new ModelLimit(128000, null, 4096));
        HarnessEngine engine = HarnessEngine.builder()
            .llmClient(mockLlm)
            .model(model)
            .build();

        AgentDef agent = AgentDefBuilder.create("streaming-agent")
            .systemPrompt("你是天气助手。")
            .addTool(new WeatherTool())
            .maxSteps(5)
            .build();

        // 收集所有事件
        List<AgentEvent> collectedEvents = new ArrayList<>();

        Session session = engine.createSession(agent);
        Message result = engine.runStreaming(session, agent, "上海天气？", event -> {
            collectedEvents.add(event);
            // 模拟 UI 中的事件处理
            switch (event) {
                case AgentEvent.StepStart ss ->
                    System.out.println("[Step " + ss.step() + " 开始]");
                case AgentEvent.TextDelta td ->
                    System.out.print(td.delta()); // 打字机效果
                case AgentEvent.ToolCallStarted tc ->
                    System.out.println("\n[调用工具: " + tc.tool() + "]");
                case AgentEvent.ToolCallCompleted tc ->
                    System.out.println("[工具完成: " + tc.tool() + "]");
                case AgentEvent.Finished f ->
                    System.out.println("\n[完成: " + f.reason() + "]");
                default -> {} // 其他事件忽略
            }
        });

        // ── 验证事件流 ──
        assertNotNull(result);

        // 应该有 StepStart 事件（每轮 LLM 调用前发射）
        assertTrue(collectedEvents.stream().anyMatch(e -> e instanceof AgentEvent.StepStart),
            "应收到 StepStart 事件");

        // 应该有 TextDelta 事件（LLM 文本流输出）
        long textDeltas = collectedEvents.stream()
            .filter(e -> e instanceof AgentEvent.TextDelta)
            .count();
        assertTrue(textDeltas >= 1, "应收到 TextDelta 事件");

        // 应该有 ToolCallStarted/Completed 事件
        assertTrue(collectedEvents.stream().anyMatch(e -> e instanceof AgentEvent.ToolCallStarted),
            "应收到 ToolCallStarted 事件");
        assertTrue(collectedEvents.stream().anyMatch(e -> e instanceof AgentEvent.ToolCallCompleted),
            "应收到 ToolCallCompleted 事件");

        // 应该有 Finished 事件
        assertTrue(collectedEvents.stream().anyMatch(e -> e instanceof AgentEvent.Finished),
            "应收到 Finished 事件");

        engine.shutdown();
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // Demo 4: 多轮对话 —— Session 保持上下文
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * 展示如何用同一个 Session 进行多轮对话，让 Agent 记住上下文。
     *
     * <p>关键机制：
     * <ul>
     *   <li>每次 run() 后，消息会存入 {@link com.aliyun.odps.agentic.session.MessageStore}</li>
     *   <li>下一轮调用时，RunLoop 会从 MessageStore 加载完整历史</li>
     *   <li>需要用 engine.getMessages() 获取最新消息列表，重建 Session</li>
     * </ul>
     */
    @Test
    @Timeout(10)
    void demo4_multiTurnConversation() {
        int[] step = {0};
        LLMClient mockLlm = new LLMClient() {
            @Override
            public void stream(LlmRequest request, Consumer<LLMEvent> events) {
                if (isTitleRequest(request)) { handleTitleRequest(events); return; }

                step[0]++;
                if (step[0] == 1) {
                    events.accept(new LLMEvent.TextDelta("好的，我记住了，你最喜欢的语言是 Rust。"));
                    events.accept(new LLMEvent.Finish("end-turn"));
                } else {
                    // 第 2 轮：request.messages() 中包含了第 1 轮的完整对话历史
                    // 真实 LLM 会根据历史回答，这里我们直接模拟正确答案
                    events.accept(new LLMEvent.TextDelta("你最喜欢的语言是 Rust。"));
                    events.accept(new LLMEvent.Finish("end-turn"));
                }
            }

            @Override
            public boolean supports(String providerId) { return true; }
        };

        Model model = Model.of("mock", "mock-model", new ModelLimit(128000, null, 4096));
        HarnessEngine engine = HarnessEngine.builder()
            .llmClient(mockLlm)
            .model(model)
            .build();

        AgentDef agent = AgentDefBuilder.create("memory-agent")
            .systemPrompt("你是一个记忆力很好的助手。记住用户告诉你的信息。")
            .build();

        // ── 第 1 轮 ──
        Session session = engine.createSession(agent);
        Message result1 = engine.run(session, agent, "我最喜欢的编程语言是 Rust。");
        assertNotNull(result1);
        assertTrue(result1.getTextContent().contains("Rust"));

        // ── 第 2 轮：重建 Session（携带历史消息）──
        // engine.getMessages() 从 MessageStore 获取最新的完整消息列表
        Session session2 = new Session(
            session.id(), session.title(), session.status(),
            engine.getMessages(session.id()),
            session.agent(), session.model(), session.cost(), session.tokens(),
            session.createdAt(), session.updatedAt(), session.permission()
        );
        Message result2 = engine.run(session2, agent, "我最喜欢的语言是什么？");
        assertNotNull(result2);
        assertTrue(result2.getTextContent().contains("Rust"));

        // 验证 LLM 被调用了 2 次（两轮对话，不含标题生成）
        assertEquals(2, step[0]);

        engine.shutdown();
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // Demo 5: runWithEvents —— 批量收集事件 + 最终消息
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * runWithEvents() 是 run() 和 runStreaming() 之间的折中方案：
     * 不需要实时流式回调，但想在结束后分析所有事件。
     *
     * <p>返回 {@link HarnessEngine.RunResult}，包含：
     * <ul>
     *   <li>finalMessage — 最终助手消息</li>
     *   <li>events — 运行过程中产生的全部事件列表</li>
     * </ul>
     */
    @Test
    @Timeout(10)
    void demo5_runWithEvents() {
        LLMClient mockLlm = new LLMClient() {
            @Override
            public void stream(LlmRequest request, Consumer<LLMEvent> events) {
                events.accept(new LLMEvent.TextDelta("2 + 3 = 5"));
                events.accept(new LLMEvent.Finish("end-turn"));
            }

            @Override
            public boolean supports(String providerId) { return true; }
        };

        Model model = Model.of("mock", "mock-model", new ModelLimit(128000, null, 4096));
        HarnessEngine engine = HarnessEngine.builder()
            .llmClient(mockLlm)
            .model(model)
            .build();

        AgentDef agent = AgentDefBuilder.create("calc-agent")
            .systemPrompt("你是一个计算助手。")
            .build();

        Session session = engine.createSession(agent);
        HarnessEngine.RunResult runResult = engine.runWithEvents(session, agent, "2 + 3 = ?");

        // 最终消息
        assertNotNull(runResult.finalMessage());
        assertTrue(runResult.finalMessage().getTextContent().contains("5"));

        // 事件列表 —— 可用于后处理：日志分析、计费统计、调试等
        assertFalse(runResult.events().isEmpty());
        System.out.println("共产生 " + runResult.events().size() + " 个事件：");
        for (AgentEvent event : runResult.events()) {
            System.out.println("  - " + event.getClass().getSimpleName());
        }

        engine.shutdown();
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // 自定义工具实现
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * 天气查询工具 —— ToolDef 接口实现示例。
     *
     * <p>实现 ToolDef 需要提供 4 样东西：
     * <ol>
     *   <li><b>getId()</b> — 工具的唯一标识，LLM 通过这个名字引用工具</li>
     *   <li><b>getDescription()</b> — 告诉 LLM 这个工具能做什么（影响 LLM 决策）</li>
     *   <li><b>getParametersSchema()</b> — JSON Schema，约束 LLM 生成的参数格式</li>
     *   <li><b>execute()</b> — 实际执行逻辑，返回 ToolResult</li>
     * </ol>
     */
    static class WeatherTool implements ToolDef {
        volatile boolean wasCalled = false;

        @Override
        public String getId() { return "get_weather"; }

        @Override
        public String getDescription() {
            return "查询指定城市的当前天气。传入城市名称，返回天气状况和温度。";
        }

        @Override
        public ObjectNode getParametersSchema() {
            // JSON Schema 格式，LLM 会据此生成结构化参数
            ObjectNode schema = MAPPER.createObjectNode();
            schema.put("type", "object");
            ObjectNode props = MAPPER.createObjectNode();
            props.putObject("city")
                .put("type", "string")
                .put("description", "城市名称，如 北京、上海");
            schema.set("properties", props);
            schema.set("required", MAPPER.createArrayNode().add("city"));
            return schema;
        }

        @Override
        public ToolResult execute(JsonNode args, ToolContext context) {
            wasCalled = true;
            String city = args.path("city").asText("未知城市");

            // 模拟天气数据（真实场景中这里会调用天气 API）
            String weather = switch (city) {
                case "北京" -> "晴，25°C，湿度 40%";
                case "上海" -> "多云，22°C，湿度 65%";
                case "广州" -> "小雨，28°C，湿度 80%";
                default -> "晴，20°C";
            };

            // ToolResult.of(title, output)
            //   title: 简短摘要，显示在 UI 中
            //   output: 完整内容，传回给 LLM
            return ToolResult.of(city + " 天气", weather);
        }
    }
}
