package com.aliyun.odps.agentic.example;

import com.aliyun.odps.agentic.HarnessEngine;
import com.aliyun.odps.agentic.agent.AgentDef;
import com.aliyun.odps.agentic.agent.AgentDefBuilder;
import com.aliyun.odps.agentic.llm.Model;
import com.aliyun.odps.agentic.llm.ModelLimit;
import com.aliyun.odps.agentic.llm.SseLlmClient;
import com.aliyun.odps.agentic.llm.provider.OpenAICompatible;
import com.aliyun.odps.agentic.session.AgentEvent;
import java.nio.file.Path;

public class QuickStart {
    public static void main(String[] args) {
        Model model = OpenAICompatible.configure("my-provider",
            System.getenv("AGENT_API_URL"), System.getenv("AGENT_API_KEY"))
            .model(System.getenv("AGENT_MODEL"), new ModelLimit(
                Integer.parseInt(System.getenv("MODEL_CONTEXT_TOKENS")), null,
                Integer.parseInt(System.getenv("MODEL_OUTPUT_TOKENS"))));

        AgentDef agent = AgentDefBuilder.create("workspace-assistant")
            .systemPrompt("阅读工作区并回答问题。只使用读取和搜索工具。")
            .includeTools("read", "glob", "grep")
            .maxSteps(20)
            .build();

        HarnessEngine engine = HarnessEngine.builder()
            .workDir(Path.of("."))
            .llmClient(new SseLlmClient())
            .model(model)
            .build();
        try {
            var session = engine.createSession(agent);
            engine.runStreaming(session, agent, "阅读 README 并概括项目用途。", event -> {
                if (event instanceof AgentEvent.TextDelta delta) {
                    System.out.print(delta.delta());
                }
            });
        } finally {
            engine.shutdown();
        }
    }
}
