package com.aliyun.odps.agentic.agent;

import com.aliyun.odps.agentic.memory.MemoryConfig;
import com.aliyun.odps.agentic.permission.Rule;
import com.aliyun.odps.agentic.mcp.McpServerConfig;
import com.aliyun.odps.agentic.skill.SkillConfig;
import com.aliyun.odps.agentic.tool.ToolDef;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * {@link AgentDef} 的构建器，提供链式 API 来组装代理定义。
 * 可用于配置代理名称、系统提示词、工具、技能、模型和权限等信息。
 */
public class AgentDefBuilder {

    private String name;
    private String description = "";
    private Function<String, String> systemPromptProvider;
    private final List<ToolDef> tools = new ArrayList<>();
    private final List<McpServerConfig> mcpServers = new ArrayList<>();
    private final List<SkillConfig> skills = new ArrayList<>();
    private final List<String> skillRepositories = new ArrayList<>();
    private final List<Rule> permissionRules = new ArrayList<>();
    private int maxSteps = 200;
    private ModelConfig model = new ModelConfig("default", "default");
    private Double temperature;
    private Double topP;
    private Boolean enableThinking;
    private MemoryConfig memoryConfig = MemoryConfig.defaultConfig();
    private final List<String> instructionFiles = new ArrayList<>(List.of("AGENTS.md"));
    private final Set<String> includedTools = new LinkedHashSet<>();

    /**
     * 创建一个新的构建器并设置代理名称。
     *
     * @param name 代理名称
     * @return 构建器实例
     */
    public static AgentDefBuilder create(String name) {
        return new AgentDefBuilder().name(name);
    }

    /**
     * 设置代理名称。
     *
     * @param name 代理名称
     * @return 当前构建器
     */
    public AgentDefBuilder name(String name) {
        this.name = name;
        return this;
    }

    /**
     * 设置代理描述。
     *
     * @param description 代理描述
     * @return 当前构建器
     */
    public AgentDefBuilder description(String description) {
        this.description = description;
        return this;
    }

    /**
     * 设置系统提示词提供函数。
     *
     * @param provider 根据模型名称生成系统提示词的函数
     * @return 当前构建器
     */
    public AgentDefBuilder systemPrompt(Function<String, String> provider) {
        this.systemPromptProvider = provider;
        return this;
    }

    /**
     * 直接设置固定的系统提示词内容。
     *
     * @param prompt 系统提示词
     * @return 当前构建器
     */
    public AgentDefBuilder systemPrompt(String prompt) {
        this.systemPromptProvider = model -> prompt;
        return this;
    }

    /**
     * 添加一个工具。
     *
     * @param tool 工具定义
     * @return 当前构建器
     */
    public AgentDefBuilder addTool(ToolDef tool) {
        this.tools.add(tool);
        return this;
    }

    /**
     * 替换全部工具列表。
     *
     * @param tools 工具列表
     * @return 当前构建器
     */
    public AgentDefBuilder tools(List<ToolDef> tools) {
        this.tools.clear();
        this.tools.addAll(tools);
        return this;
    }

    /**
     * 添加一个 MCP 服务器配置。
     *
     * @param server MCP 服务器配置
     * @return 当前构建器
     */
    public AgentDefBuilder addMcpServer(McpServerConfig server) {
        this.mcpServers.add(server);
        return this;
    }

    /**
     * 添加一个技能配置。
     *
     * @param skill 技能配置
     * @return 当前构建器
     */
    public AgentDefBuilder addSkill(SkillConfig skill) {
        this.skills.add(skill);
        return this;
    }

    /**
     * 添加一个远程技能仓库。
     *
     * @param repo 技能仓库地址
     * @return 当前构建器
     */
    public AgentDefBuilder addSkillRepository(String repo) {
        this.skillRepositories.add(repo);
        return this;
    }

    /**
     * 添加一条权限规则。
     *
     * @param rule 权限规则
     * @return 当前构建器
     */
    public AgentDefBuilder addPermissionRule(Rule rule) {
        this.permissionRules.add(rule);
        return this;
    }

    /**
     * 设置运行循环的最大步数。
     *
     * @param maxSteps 最大步数
     * @return 当前构建器
     */
    public AgentDefBuilder maxSteps(int maxSteps) {
        this.maxSteps = maxSteps;
        return this;
    }

    /**
     * 设置模型配置。
     *
     * @param model 模型配置
     * @return 当前构建器
     */
    public AgentDefBuilder model(ModelConfig model) {
        this.model = model;
        return this;
    }

    /**
     * 通过提供者 ID 和模型 ID 设置模型配置。
     *
     * @param providerId 提供者 ID
     * @param modelId 模型 ID
     * @return 当前构建器
     */
    public AgentDefBuilder model(String providerId, String modelId) {
        this.model = new ModelConfig(providerId, modelId);
        return this;
    }

    /**
     * 设置 temperature 参数。
     *
     * @param temperature temperature 值
     * @return 当前构建器
     */
    public AgentDefBuilder temperature(double temperature) {
        this.temperature = temperature;
        return this;
    }

    /**
     * 设置 top_p 参数。
     *
     * @param topP top_p 值
     * @return 当前构建器
     */
    public AgentDefBuilder topP(double topP) {
        this.topP = topP;
        return this;
    }

    /**
     * 强制开启或关闭思考链（reasoning）。不调用则不表态，随提供者默认值。
     *
     * @param enableThinking {@code true} 开、{@code false} 关、{@code null} 不表态
     * @return 当前构建器
     */
    public AgentDefBuilder enableThinking(Boolean enableThinking) {
        this.enableThinking = enableThinking;
        return this;
    }

    /**
     * 设置记忆配置。
     *
     * @param config 记忆配置
     * @return 当前构建器
     */
    public AgentDefBuilder memoryConfig(MemoryConfig config) {
        this.memoryConfig = config;
        return this;
    }

    /**
     * 添加一个指令文件。
     *
     * @param file 指令文件名
     * @return 当前构建器
     */
    public AgentDefBuilder addInstructionFile(String file) {
        this.instructionFiles.add(file);
        return this;
    }

    /**
     * 设置工具白名单，仅允许这些工具 ID 可用。
     * 未调用此方法或传入空数组时，表示允许所有工具。
     *
     * @param toolIds 允许的工具 ID 列表
     * @return 当前构建器
     */
    public AgentDefBuilder includeTools(String... toolIds) {
        this.includedTools.clear();
        for (String id : toolIds) {
            this.includedTools.add(id);
        }
        return this;
    }

    /**
     * 构建不可变的 {@link AgentDef} 实例。
     *
     * @return 构建完成的代理定义
     */
    public AgentDef build() {
        if (name == null || name.isBlank()) {
            throw new IllegalStateException("Agent name is required");
        }

        final var capturedName = this.name;
        final var capturedDesc = this.description;
        final var capturedPrompt = this.systemPromptProvider;
        final var capturedTools = List.copyOf(this.tools);
        final var capturedMcp = List.copyOf(this.mcpServers);
        final var capturedSkills = List.copyOf(this.skills);
        final var capturedRepos = List.copyOf(this.skillRepositories);
        final var capturedRules = List.copyOf(this.permissionRules);
        final var capturedMaxSteps = this.maxSteps;
        final var capturedModel = this.model;
        final var capturedTemp = this.temperature;
        final var capturedTopP = this.topP;
        final var capturedThinking = this.enableThinking;
        final var capturedMemConfig = this.memoryConfig;
        final var capturedInstrFiles = List.copyOf(this.instructionFiles);
        final var capturedIncludedTools = Set.copyOf(this.includedTools);

        return new AgentDef() {
            @Override public String getName() { return capturedName; }
            @Override public String getDescription() { return capturedDesc; }
            @Override public String getSystemPrompt(Function<String, String> modelProvider) {
                return capturedPrompt != null ? capturedPrompt.apply(modelProvider.apply(capturedModel.modelId()))
                    : AgentDef.super.getSystemPrompt(modelProvider);
            }
            @Override public List<ToolDef> getTools() { return capturedTools; }
            @Override public List<McpServerConfig> getMcpServers() { return capturedMcp; }
            @Override public List<SkillConfig> getSkills() { return capturedSkills; }
            @Override public List<String> getSkillRepositories() { return capturedRepos; }
            @Override public List<Rule> getPermissionRules() { return capturedRules; }
            @Override public int getMaxSteps() { return capturedMaxSteps; }
            @Override public ModelConfig getModel() { return capturedModel; }
            @Override public Optional<Double> getTemperature() { return Optional.ofNullable(capturedTemp); }
            @Override public Optional<Double> getTopP() { return Optional.ofNullable(capturedTopP); }
            @Override public Optional<Boolean> getEnableThinking() { return Optional.ofNullable(capturedThinking); }
            @Override public MemoryConfig getMemoryConfig() { return capturedMemConfig; }
            @Override public List<String> getInstructionFiles() { return capturedInstrFiles; }
            @Override public Set<String> getIncludedTools() { return capturedIncludedTools; }
        };
    }
}
