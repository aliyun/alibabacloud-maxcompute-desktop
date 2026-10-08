package com.aliyun.odps.agentic.skill.manifest;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Skill 描述清单（参考 Anthropic Skills API）。
 * <p>
 * 一份 manifest 描述一个 Skill 的元数据 + 实现指针 + 沙箱级别。
 * 来源：classpath {@code META-INF/skills/*.yml} （内置）或用户目录 {@code ~/.maxquery/skills/*.yml}。
 *
 * <p>关键字段：
 * <ul>
 *   <li>{@code name} – 唯一名称，用作 ReAct tool 名</li>
 *   <li>{@code implementation} – 实现来源；可选格式：
 *     <ul>
 *       <li>{@code FQCN}：{@code your.application.PlanTool}（实现 {@code Skill} 接口的类）</li>
 *       <li>{@code tool:NAME}：引用 {@code ToolSkillRegistrar} 中已注册的同名 ToolSkill</li>
 *     </ul>
 *   </li>
 *   <li>{@code sandboxLevel} – 安全级别（红线 #13）：
 *     <ul>
 *       <li>{@code builtin}：仅允许 META-INF（classpath）来源</li>
 *       <li>{@code user}：用户目录加载，受限网络/IO</li>
 *       <li>{@code external}：第三方（暂未启用，保留枚举）</li>
 *     </ul>
 *   </li>
 *   <li>{@code source} – 来源标记（不在 YAML 内，加载器填充）</li>
 * </ul>
 */
public class SkillManifest {

    /** 内置 sandbox：META-INF/skills/*.yml 唯一允许的级别。 */
    public static final String SANDBOX_BUILTIN = "builtin";
    /** 用户 sandbox：~/.maxquery/skills/*.yml；禁止网络/Shell/写文件。 */
    public static final String SANDBOX_USER = "user";
    /** 外部 sandbox：保留扩展位。 */
    public static final String SANDBOX_EXTERNAL = "external";

    /** 来源：从 classpath 加载（内置）。 */
    public static final String SOURCE_BUILTIN = "BUILTIN";
    /** 来源：从用户目录加载。 */
    public static final String SOURCE_USER = "USER";

    private String name;
    private String description;
    private String version = "1.0";
    private List<String> tags;
    private Set<String> requiredCapabilities;
    private String sandboxLevel = SANDBOX_BUILTIN;
    private String implementation;
    private Map<String, Object> inputSchema;
    private Map<String, Object> outputSchema;

    /** Skill依赖的Tool Skills名称列表（提示LLM确保这些工具可用）。 */
    private List<String> requires;
    /** 常与此Skill配合使用的其他Prompt Skills名称列表（LLM编排提示）。 */
    private List<String> composedWith;
    /** 该Skill激活时允许的工具子集（逗号分隔字符串）。 */
    private String allowedTools;

    /** 加载来源（不在 YAML 内）。 */
    private String source = SOURCE_BUILTIN;
    /** 物理来源路径（classpath URL 或文件路径，便于 reload）。 */
    private String sourcePath;
    /** 启用状态（per-session 可覆盖）。 */
    private boolean enabled = true;

    public SkillManifest() {
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }

    public List<String> getTags() { return tags; }
    public void setTags(List<String> tags) { this.tags = tags; }

    public Set<String> getRequiredCapabilities() { return requiredCapabilities; }
    public void setRequiredCapabilities(Set<String> requiredCapabilities) { this.requiredCapabilities = requiredCapabilities; }

    public String getSandboxLevel() { return sandboxLevel; }
    public void setSandboxLevel(String sandboxLevel) { this.sandboxLevel = sandboxLevel; }

    public String getImplementation() { return implementation; }
    public void setImplementation(String implementation) { this.implementation = implementation; }

    public Map<String, Object> getInputSchema() { return inputSchema; }
    public void setInputSchema(Map<String, Object> inputSchema) { this.inputSchema = inputSchema; }

    public Map<String, Object> getOutputSchema() { return outputSchema; }
    public void setOutputSchema(Map<String, Object> outputSchema) { this.outputSchema = outputSchema; }

    public List<String> getRequires() { return requires; }
    public void setRequires(List<String> requires) { this.requires = requires; }

    public List<String> getComposedWith() { return composedWith; }
    public void setComposedWith(List<String> composedWith) { this.composedWith = composedWith; }

    public String getAllowedTools() { return allowedTools; }
    public void setAllowedTools(String allowedTools) { this.allowedTools = allowedTools; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public String getSourcePath() { return sourcePath; }
    public void setSourcePath(String sourcePath) { this.sourcePath = sourcePath; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    /** 是否为 ToolSkill 引用（{@code tool:NAME} 格式）。 */
    public boolean isToolReference() {
        return implementation != null && implementation.startsWith("tool:");
    }

    /** 是否为 Prompt Skill（{@code prompt:PATH} 格式 — SKILL.md 指令文本，非 tool）。 */
    public boolean isPromptSkill() {
        return implementation != null && implementation.startsWith("prompt:");
    }

    /** 提取 Prompt Skill 的 SKILL.md 绝对路径。 */
    public String getPromptSkillPath() {
        if (!isPromptSkill()) return null;
        return implementation.substring("prompt:".length());
    }

    /** 提取 tool 引用名。 */
    public String getToolReferenceName() {
        if (!isToolReference()) return null;
        return implementation.substring("tool:".length());
    }

    /** 转为前端可见的 metadata Map（不暴露 sourcePath 等内部字段以外的细节）。 */
    public Map<String, Object> toMetadataMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", name);
        map.put("description", description);
        map.put("version", version);
        map.put("tags", tags == null ? List.of() : tags);
        map.put("requiredCapabilities",
            requiredCapabilities == null ? new LinkedHashSet<>() : requiredCapabilities);
        map.put("sandboxLevel", sandboxLevel);
        map.put("implementation", implementation);
        map.put("source", source);
        map.put("enabled", enabled);
        if (inputSchema != null) map.put("inputSchema", inputSchema);
        if (outputSchema != null) map.put("outputSchema", outputSchema);
        if (requires != null && !requires.isEmpty()) map.put("requires", requires);
        if (composedWith != null && !composedWith.isEmpty()) map.put("composedWith", composedWith);
        if (allowedTools != null) map.put("allowedTools", allowedTools);
        return map;
    }
}
