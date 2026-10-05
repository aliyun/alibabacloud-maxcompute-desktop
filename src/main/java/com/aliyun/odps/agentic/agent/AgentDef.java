package com.aliyun.odps.agentic.agent;

import com.aliyun.odps.agentic.memory.MemoryConfig;
import com.aliyun.odps.agentic.permission.Rule;
import com.aliyun.odps.agentic.mcp.McpServerConfig;
import com.aliyun.odps.agentic.skill.SkillConfig;
import com.aliyun.odps.agentic.tool.ToolDef;
import com.aliyun.odps.agentic.session.RunPolicy;
import com.aliyun.odps.agentic.session.ToolBatchExecutor;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * 代理定义 SPI，用于声明代理的能力、行为和运行配置。
 *
 * <p>用户通过实现此接口来定义自己的代理。Harness SDK 提供固定运行时，
 * 而 {@code AgentDef} 负责描述代理名称、系统提示词、工具、技能等内容。
 *
 * <p>除 {@code getName()} 外，其余方法都提供了合理的默认实现。
 */
public interface AgentDef {

    /**
     * 返回代理名称，用于标识和路由。
     */
    String getName();

    /**
     * 返回代理描述，用于界面展示或选择。
     */
    default String getDescription() {
        return "";
    }

    /**
     * 构建当前代理的系统提示词。
     *
     * @param modelProvider 接收模型 ID 并返回基础提示词的函数
     * @return 完整的系统提示词内容
     */
    default String getSystemPrompt(Function<String, String> modelProvider) {
        return modelProvider.apply(getModel().modelId());
    }

    /** Use an application-built prompt verbatim when it already contains its envelope. */
    default Optional<String> getExactSystemPrompt() {
        return Optional.empty();
    }

    /**
     * 返回当前代理可用的工具列表。
     */
    default List<ToolDef> getTools() {
        return List.of();
    }

    /**
     * 返回当前代理需要连接的 MCP 服务器配置。
     */
    default List<McpServerConfig> getMcpServers() {
        return List.of();
    }

    /**
     * 返回当前代理可用的技能配置。
     */
    default List<SkillConfig> getSkills() {
        return List.of();
    }

    /**
     * 返回当前代理允许发现和加载的技能名称白名单。
     * 空集合保持向后兼容，表示允许所有已配置技能。
     */
    default Set<String> getIncludedSkills() {
        return Set.of();
    }

    /**
     * 返回用于发现技能的远程技能仓库列表。
     */
    default List<String> getSkillRepositories() {
        return List.of();
    }

    /**
     * 返回代理运行模式。
     * 不同模式会影响代理的行为以及可使用的工具范围。
     */
    default String getMode() {
        return "build";
    }

    /**
     * 返回代理级别的模型配置覆盖项。
     */
    default Optional<ModelConfig> getAgentModel() {
        return Optional.empty();
    }

    /**
     * 返回当前代理的权限规则列表。
     */
    default List<Rule> getPermissionRules() {
        return List.of();
    }

    /**
     * 返回运行循环允许执行的最大步数。
     */
    default int getMaxSteps() {
        return 200;
    }

    /**
     * 返回当前代理默认使用的模型配置。
     */
    default ModelConfig getModel() {
        return new ModelConfig("default", "default");
    }

    /**
     * 返回 LLM 的 temperature 参数。
     */
    default Optional<Double> getTemperature() {
        return Optional.empty();
    }

    /**
     * 返回 LLM 的 top_p 参数。
     */
    default Optional<Double> getTopP() {
        return Optional.empty();
    }

    /**
     * 返回是否强制开启/关闭思考链（reasoning）。
     *
     * <p>{@code empty} 表示不表态 —— 由提供者的默认行为决定；{@code TRUE}/{@code FALSE}
     * 会作为显式参数下发给支持该开关的提供者。思考链显著影响首字延迟，交互式场景
     * （侧栏助手一类）常需要显式关闭，因此这里必须能表达"关"，而不只是"不开"。
     */
    default Optional<Boolean> getEnableThinking() {
        return Optional.empty();
    }

    /**
     * 返回当前代理的记忆配置。
     */
    default MemoryConfig getMemoryConfig() {
        return MemoryConfig.defaultConfig();
    }

    /**
     * 返回需要注入到系统提示词中的指令文件列表。
     */
    default List<String> getInstructionFiles() {
        return List.of("AGENTS.md");
    }

    /**
     * 返回工具白名单。
     * 空集合表示默认允许所有工具。
     */
    default Set<String> getIncludedTools() {
        return Set.of();
    }

    /**
     * 返回注入到 {@code ToolContext} 的额外上下文数据。
     * 可用于向工具实现传递代理特定的扩展信息。
     */
    default Map<String, Object> getToolContextExtra() {
        return Map.of();
    }

    /**
     * Application completion policy. The default preserves the SDK's ordinary
     * end-turn behavior; domain agents may require additional evidence.
     */
    default RunPolicy getRunPolicy() {
        return (session, agent, candidate, history) -> RunPolicy.FinishDecision.accept();
    }

    /** Let a host project and compact provider input while retaining full SDK history. */
    default boolean hostManagesContextProjection() {
        return false;
    }

    /** Preserve a host provider's typed failure instead of recording a generic assistant error. */
    default boolean propagateLlmFailures() {
        return false;
    }

    /** Per-run host tool pipeline, when the embedding application owns execution. */
    default Optional<ToolBatchExecutor> getToolBatchExecutor() {
        return Optional.empty();
    }

    /** Exclude SDK bundled tools when the host owns the complete tool catalog. */
    default boolean includeBuiltinTools() {
        return true;
    }

    /** Hosts with their own session-title service can disable SDK title calls. */
    default boolean generateTitle() {
        return true;
    }
}
