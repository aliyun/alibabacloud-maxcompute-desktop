package com.aliyun.odps.agentic.session;

/**
 * 达到最大步数时注入给模型的提示词。
 *
 * <p>该提示会强制模型停止工具调用，并仅返回文本总结。
 */
public final class MaxStepsPrompt {

    private MaxStepsPrompt() {}

    /**
     * 在最后一步作为助手消息注入的固定提示文本。
     */
    public static final String MAX_STEPS = """
MAXIMUM STEPS REACHED

The runtime has exhausted this run's step budget and disabled tools.
Return text ONLY; do not request another tool call in this closing response.

Tell the user that execution stopped at the step limit. Report confirmed results,
remaining work and any blocker, followed by a concrete next action. Keep attempted
operations separate from completed outcomes, and do not claim unfinished work succeeded.""";
}
