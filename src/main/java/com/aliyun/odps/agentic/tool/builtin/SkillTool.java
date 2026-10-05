package com.aliyun.odps.agentic.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.aliyun.odps.agentic.skill.SkillInfo;
import com.aliyun.odps.agentic.skill.SkillLoader;
import com.aliyun.odps.agentic.tool.ToolContext;
import com.aliyun.odps.agentic.tool.ToolDef;
import com.aliyun.odps.agentic.tool.ToolResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.stream.Stream;

/**
 * 内置技能工具，用于加载并向模型暴露技能内容。
 * 它会返回技能正文、基础目录以及部分关联文件列表，便于后续读取技能资源。
 */
public class SkillTool implements ToolDef {

    private static final String ID = "skill";
    /** ToolContext extra key carrying the current AgentDef's allowed skill names. */
    public static final String INCLUDED_SKILLS_CONTEXT_KEY = "agentic.includedSkills";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String DESCRIPTION = ResourceLoader.load("tools/skill.txt");
    private static final int FILE_LIMIT = 10;
    private final SkillLoader skillLoader;

    /**
     * 创建技能工具。
     *
     * @param skillLoader 技能加载器
     */
    public SkillTool(SkillLoader skillLoader) {
        this.skillLoader = skillLoader;
    }

    /**
     * 返回工具 ID。
     */
    @Override
    public String getId() { return ID; }

    @Override
    public String getDescription() {
        return DESCRIPTION;
    }

    /**
     * 返回 skill 工具的参数 Schema。
     *
     * <p>对应参数示例：
     * <pre>{@code
     * {
     *   "name": "code-review"
     * }
     * }</pre>
     */
    @Override
    public ObjectNode getParametersSchema() {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");

        ObjectNode properties = MAPPER.createObjectNode();
        ObjectNode nameProp = MAPPER.createObjectNode();
        nameProp.put("type", "string");
        nameProp.put("description", "The name of the skill from available_skills");
        properties.set("name", nameProp);

        schema.set("properties", properties);
        schema.put("additionalProperties", false);

        var required = MAPPER.createArrayNode();
        required.add("name");
        schema.set("required", required);

        return schema;
    }

    /**
     * 加载并返回指定名称的技能内容。
     *
     * <p>执行流程：
     * <ol>
     *   <li>通过 {@link SkillLoader#discover()} 发现所有可用技能</li>
     *   <li>按 {@code name} 参数查找匹配的技能；未找到时返回可用技能列表</li>
     *   <li>读取技能的 SKILL.md 正文内容</li>
     *   <li>如果技能来自 SKILL.md 文件，列出其所在目录的关联文件（最多 {@value FILE_LIMIT} 个）</li>
     *   <li>将技能内容、基础目录和文件列表组装为 {@code <skill_content>} XML 格式输出</li>
     * </ol>
     */
    @Override
    public ToolResult execute(JsonNode args, ToolContext context) {
        if (args == null || !args.has("name")) {
            return ToolResult.error("Missing required parameter: name");
        }

        String skillName = args.get("name").asText();

        Collection<?> includedSkills = includedSkills(context);
        if (includedSkills != null && !includedSkills.contains(skillName)) {
            return ToolResult.error("Skill is not enabled for this agent profile: " + skillName);
        }

        try {
            // Look up the skill
            List<SkillInfo> skills = skillLoader.discover();
            SkillInfo skill = skills.stream()
                .filter(s -> s.name().equals(skillName))
                .findFirst()
                .orElse(null);

            if (skill == null) {
                // List available skills
                String available = skills.stream()
                    .map(SkillInfo::name)
                    .reduce((a, b) -> a + ", " + b)
                    .orElse("none");
                return ToolResult.error("Skill not found: " + skillName
                    + ". Available skills: " + available);
            }

            // Surface the skill's directory and a sampled file list so bundled
            // resources (scripts/, reference/) can be read. Files are only
            // listed when the skill comes from a SKILL.md file, 用于向模型暴露技能相关文件。
            Path location = Path.of(skill.location());
            Path directory = location.getParent();
            List<String> files = List.of();
            if (directory != null && "SKILL.md".equals(location.getFileName().toString())) {
                files = listSkillFiles(directory);
            }

            return ToolResult.of("Loaded skill: " + skill.name(),
                toModelOutput(skill, directory, files));
        } catch (Exception e) {
            return ToolResult.error("Failed to load skill: " + e.getMessage());
        }
    }

    private static Collection<?> includedSkills(ToolContext context) {
        if (context == null || context.extra() == null) return null;
        Object value = context.extra().get(INCLUDED_SKILLS_CONTEXT_KEY);
        return value instanceof Collection<?> collection ? collection : null;
    }

    /**
     * 渲染技能输出，包含完整内容、基础目录 URI 和部分文件列表。
     */
    private static String toModelOutput(SkillInfo skill, Path directory, List<String> files) {
        StringBuilder sb = new StringBuilder();
        sb.append("<skill_content name=\"").append(skill.name()).append("\">\n");
        sb.append("# Skill: ").append(skill.name()).append("\n\n");
        sb.append(skill.content().trim()).append("\n\n");
        if (directory != null) {
            sb.append("Base directory for this skill: ").append(directory.toUri()).append("\n");
            sb.append("Relative paths in this skill (e.g., scripts/, reference/) "
                + "are relative to this base directory.\n");
            sb.append("Note: file list is sampled.\n\n");
        }
        sb.append("<skill_files>\n");
        for (String file : files) {
            sb.append("<file>").append(file).append("</file>\n");
        }
        sb.append("</skill_files>\n");
        sb.append("</skill_content>");
        return sb.toString();
    }

    private static List<String> listSkillFiles(Path directory) {
        try (Stream<Path> walk = Files.walk(directory)) {
            return walk
                .filter(Files::isRegularFile)
                .filter(p -> !"SKILL.md".equals(p.getFileName().toString()))
                .map(p -> p.toAbsolutePath().toString())
                .sorted()
                .limit(FILE_LIMIT)
                .toList();
        } catch (IOException e) {
            return List.of();
        }
    }
}
