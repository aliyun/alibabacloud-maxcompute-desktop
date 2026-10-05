package com.aliyun.odps.agentic.skill;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 技能加载器 -- 负责从本地目录发现、加载并缓存技能。
 * 支持配置目录、工作区目录以及缓存中的技能统一管理。
 */
public class SkillLoader {

    private static final Logger log = LoggerFactory.getLogger(SkillLoader.class);
    private static final String SKILL_FILE = "SKILL.md";
    private static final String SKILL_PATTERN = "**/" + SKILL_FILE;

    private final List<Path> skillPaths;
    private final List<String> repositories;
    private final Map<String, SkillInfo> cache = new ConcurrentHashMap<>();

    public SkillLoader(List<Path> skillPaths, List<String> repositories) {
        this.skillPaths = skillPaths != null ? skillPaths : List.of();
        this.repositories = repositories != null ? repositories : List.of();
    }

    /**
     * 从已配置路径中发现全部可用技能。
     *
     * @return 技能列表
     */
    public List<SkillInfo> discover() {
        List<SkillInfo> skills = new ArrayList<>();

        for (Path basePath : skillPaths) {
            if (!Files.exists(basePath) || !Files.isDirectory(basePath)) {
                continue;
            }

            try {
                Files.walkFileTree(basePath, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        if (file.getFileName().toString().equals(SKILL_FILE)) {
                            try {
                                SkillInfo skill = parseSkillFile(file);
                                if (skill != null) {
                                    skills.add(skill);
                                    cache.put(skill.name(), skill);
                                }
                            } catch (Exception e) {
                                log.warn("Failed to parse skill file: {}", file, e);
                            }
                        }
                        return FileVisitResult.CONTINUE;
                    }
                });
            } catch (IOException e) {
                log.warn("Failed to walk skill path: {}", basePath, e);
            }
        }

        Path cwd = Path.of(".").toAbsolutePath();
        discoverFromWorkspace(cwd, skills);

        log.info("Discovered {} skills", skills.size());
        return skills;
    }

    /**
     * 从工作区中的 {@code .opencode/skills/}、{@code .claude/skills/} 或 {@code .agents/skills/} 发现技能。
     */
    private void discoverFromWorkspace(Path workspace, List<SkillInfo> skills) {
        String[] dirs = {".opencode", ".claude", ".agents"};
        for (String dir : dirs) {
            Path skillDir = workspace.resolve(dir).resolve("skills");
            if (Files.exists(skillDir) && Files.isDirectory(skillDir)) {
                discoverFromDirectory(skillDir, skills);
            }
        }
    }

    private void discoverFromDirectory(Path dir, List<SkillInfo> skills) {
        try {
            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (file.getFileName().toString().equals(SKILL_FILE)) {
                        try {
                            SkillInfo skill = parseSkillFile(file);
                            if (skill != null) {
                                skills.add(skill);
                                cache.put(skill.name(), skill);
                            }
                        } catch (Exception e) {
                            log.warn("Failed to parse skill file: {}", file, e);
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            log.warn("Failed to walk directory: {}", dir, e);
        }
    }

    /**
     * 按名称加载指定技能。
     * 先检查缓存，未命中时再触发发现流程。
     *
     * @param name 技能名称
     * @return 技能信息
     */
    public Optional<SkillInfo> load(String name) {
        if (cache.containsKey(name)) {
            return Optional.of(cache.get(name));
        }

        List<SkillInfo> skills = discover();
        return skills.stream()
            .filter(s -> s.name().equals(name))
            .findFirst();
    }

    /**
     * 构建注入系统提示词的技能提示文本。
     *
     * @param skills 技能列表
     * @return 拼接后的技能提示词
     */
    public String buildSkillsPrompt(List<SkillInfo> skills) {
        if (skills == null || skills.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("## Skills\n\n");
        sb.append("You have access to the following skills. Use them when appropriate:\n\n");

        for (SkillInfo skill : skills) {
            sb.append("### ").append(skill.name()).append("\n\n");
            if (skill.description() != null && !skill.description().isBlank()) {
                sb.append(skill.description()).append("\n\n");
            }
            sb.append(skill.content()).append("\n\n");
        }

        return sb.toString();
    }

    /**
     * 解析带可选 frontmatter 的 {@code SKILL.md} 文件。
     */
    private SkillInfo parseSkillFile(Path file) throws IOException {
        String content = Files.readString(file);

        String name = null;
        String description = null;
        String body = content;

        if (content.startsWith("---")) {
            int end = content.indexOf("---", 3);
            if (end > 0) {
                String frontmatter = content.substring(3, end).trim();
                body = content.substring(end + 3).trim();

                name = parseFrontmatterField(frontmatter, "name");
                description = parseFrontmatterField(frontmatter, "description");
            }
        }

        if (name == null || name.isBlank()) {
            Path parentDir = file.getParent();
            name = parentDir != null ? parentDir.getFileName().toString() : "unknown";
        }

        return new SkillInfo(name, description, file.toString(), body);
    }

    /**
     * Parse a top-level YAML frontmatter field, including the folded ({@code >}) and literal
     * ({@code |}) block scalar forms commonly used by OpenCode-compatible skills.
     *
     * <p>This intentionally remains a small frontmatter reader rather than a general YAML parser:
     * Skill discovery only needs {@code name} and {@code description}.
     */
    private static String parseFrontmatterField(String frontmatter, String key) {
        String[] lines = frontmatter.split("\\R", -1);
        String prefix = key + ":";

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (leadingWhitespace(line) != 0 || !line.startsWith(prefix)) {
                continue;
            }

            String value = line.substring(prefix.length()).trim();
            if (!value.startsWith(">") && !value.startsWith("|")) {
                return unquote(value);
            }

            boolean folded = value.charAt(0) == '>';
            List<String> blockLines = new ArrayList<>();
            for (int j = i + 1; j < lines.length; j++) {
                String blockLine = lines[j];
                if (blockLine.isBlank()) {
                    blockLines.add("");
                    continue;
                }
                if (leadingWhitespace(blockLine) == 0) {
                    break;
                }
                blockLines.add(blockLine.trim());
            }

            if (folded) {
                return foldBlockScalar(blockLines);
            }
            return String.join("\n", blockLines).strip();
        }

        return null;
    }

    private static String foldBlockScalar(List<String> lines) {
        StringBuilder result = new StringBuilder();
        boolean previousBlank = false;
        for (String line : lines) {
            if (line.isBlank()) {
                if (!result.isEmpty() && !previousBlank) {
                    result.append('\n');
                }
                previousBlank = true;
                continue;
            }
            if (!result.isEmpty() && !previousBlank) {
                result.append(' ');
            }
            result.append(line);
            previousBlank = false;
        }
        return result.toString().strip();
    }

    private static int leadingWhitespace(String value) {
        int count = 0;
        while (count < value.length() && Character.isWhitespace(value.charAt(count))) {
            count++;
        }
        return count;
    }

    private static String unquote(String value) {
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return value.substring(1, value.length() - 1);
            }
        }
        return value;
    }
}
