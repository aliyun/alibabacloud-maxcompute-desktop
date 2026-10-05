package com.aliyun.odps.agentic.session;

import com.aliyun.odps.agentic.agent.AgentDef;
import com.aliyun.odps.agentic.llm.Model;
import com.aliyun.odps.agentic.skill.SkillInfo;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 系统提示词构建器。
 *
 * <p>按照以下顺序拼接系统提示词的各个段落：
 * <ol>
 *   <li>模型无关的基础提示词（或代理自定义提示词）</li>
 *   <li>环境信息（模型名称、工作目录、Git 状态、平台、日期）</li>
 *   <li>项目指令文件（AGENTS.md / CLAUDE.md 等）</li>
 *   <li>技能列表（XML 格式）</li>
 * </ol>
 */
public class SystemPromptBuilder {

    /**
     * 指令文件去重追踪器。
     * 避免同一助手消息中重复注入相同的指令文件。
     */
    private final Map<String, Set<String>> claims = new ConcurrentHashMap<>();

    /**
     * 清除指定消息的去重记录。
     *
     * @param messageId 消息 ID
     */
    public void clearClaims(String messageId) {
        claims.remove(messageId);
    }

    /**
     * 检查指定文件是否已在该消息中被注入。
     *
     * @param messageId 消息 ID
     * @param filePath 文件路径
     * @return 已注入返回 {@code true}
     */
    public boolean isClaimed(String messageId, String filePath) {
        Set<String> claimed = claims.get(messageId);
        return claimed != null && claimed.contains(filePath);
    }

    /**
     * 标记指定文件已在该消息中被注入。
     *
     * @param messageId 消息 ID
     * @param filePath 文件路径
     */
    public void claim(String messageId, String filePath) {
        claims.computeIfAbsent(messageId, k -> ConcurrentHashMap.newKeySet()).add(filePath);
    }

    /**
     * 使用简化参数构建系统提示词。
     *
     * @param agent 代理定义
     * @param model 模型
     * @param workDir 工作目录
     * @return 系统提示词
     */
    public String build(AgentDef agent, Model model, String workDir) {
        return build(model, agent, List.of(), workDir);
    }

    /**
     * 构建完整的系统提示词。
     *
     * @param model 当前模型
     * @param agent 代理定义
     * @param skills 已发现的技能列表
     * @param workDir 工作目录
     * @return 拼接完成的系统提示词
     */
    public String build(Model model, AgentDef agent, List<SkillInfo> skills, String workDir) {
        var exactPrompt = agent.getExactSystemPrompt();
        if (exactPrompt.isPresent()) return exactPrompt.get();
        List<String> sections = new ArrayList<>();

        // 1. 基础提示词或代理自定义提示词
        String agentPrompt = agent.getSystemPrompt(ignored -> resolveProviderPrompt(model.apiId()));
        if (agentPrompt != null && !agentPrompt.isEmpty()) {
            sections.add(agentPrompt);
        }

        // 2. 环境信息
        sections.add(buildEnvironment(model, workDir));

        // 3. 工作目录
        sections.add("<working_directory>" + workDir + "</working_directory>");

        // 4. Git 上下文（分支和远程地址）
        String gitContext = buildGitContext(workDir);
        if (gitContext != null) {
            sections.add(gitContext);
        }

        // 5. 项目指令文件
        String instructionsSection = buildInstructionFiles(workDir, agent);
        if (instructionsSection != null && !instructionsSection.isEmpty()) {
            sections.add(instructionsSection);
        }

        // 6. 技能列表
        String skillsSection = buildSkills(skills);
        if (skillsSection != null) {
            sections.add(skillsSection);
        }

        return String.join("\n", sections);
    }

    /**
     * 使用模型无关的基础提示词。具体能力由代理定义和运行时工具决定。
     *
     * @param modelApiId 模型 API ID，保留供 AgentDef 的回调接口调用
     * @return 基础提示词
     */
    public String resolveProviderPrompt(String modelApiId) {
        return loadPromptResource("default.txt");
    }

    /**
     * 构建环境信息段。
     */
    private String buildEnvironment(Model model, String workDir) {
        boolean isGitRepo = isGitRepository(workDir);
        String platform = System.getProperty("os.name", "unknown");
        String today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy/MM/dd"));

        return "You are powered by the model named " + model.apiId()
                + ". The exact model ID is " + model.providerId() + "/" + model.apiId() + "\n"
                + "Here is some useful information about the environment you are running in:\n"
                + "<env>\n"
                + "  Working directory: " + workDir + "\n"
                + "  Is directory a git repo: " + (isGitRepo ? "yes" : "no") + "\n"
                + "  Platform: " + platform + "\n"
                + "  Today's date: " + today + "\n"
                + "</env>";
    }

    /**
     * 构建技能列表段（XML 格式）。
     */
    private String buildSkills(List<SkillInfo> skills) {
        if (skills == null || skills.isEmpty()) {
            return null;
        }

        List<SkillInfo> described = skills.stream()
                .filter(s -> s.description() != null && !s.description().isEmpty())
                .sorted(Comparator.comparing(SkillInfo::name))
                .collect(Collectors.toList());

        if (described.isEmpty()) {
            return null;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Skills provide specialized instructions and workflows for specific tasks.\n");
        sb.append("Use the skill tool to load a skill when a task matches its description.\n");
        sb.append("<available_skills>\n");
        for (SkillInfo skill : described) {
            sb.append("  <skill>\n");
            sb.append("    <name>").append(skill.name()).append("</name>\n");
            sb.append("    <description>").append(skill.description()).append("</description>\n");
            sb.append("  </skill>\n");
        }
        sb.append("</available_skills>");
        return sb.toString();
    }

    /**
     * 从类路径资源加载提示词模板。
     *
     * @param filename 文件名
     * @return 模板文本
     */
    String loadPromptResource(String filename) {
        String resourcePath = "/prompts/" + filename;
        try (InputStream is = getClass().getResourceAsStream(resourcePath)) {
            if (is == null) {
                return "";
            }
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(is, StandardCharsets.UTF_8))) {
                return reader.lines().collect(Collectors.joining("\n"));
            }
        } catch (IOException e) {
            return "";
        }
    }

    /**
     * 从类路径加载代理专用提示词模板。
     *
     * @param filename 文件名
     * @return 模板文本
     */
    public String loadAgentPromptResource(String filename) {
        String resourcePath = "/prompts/agent/" + filename;
        try (InputStream is = getClass().getResourceAsStream(resourcePath)) {
            if (is == null) {
                return "";
            }
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(is, StandardCharsets.UTF_8))) {
                return reader.lines().collect(Collectors.joining("\n"));
            }
        } catch (IOException e) {
            return "";
        }
    }

    /**
     * 从工作目录读取指令文件内容。
     */
    private String readInstructionFile(String workDir, String filename) {
        try {
            Path filePath = resolveInstructionFilePath(workDir, filename);
            if (Files.exists(filePath)) {
                return Files.readString(filePath);
            }
        } catch (IOException | RuntimeException e) {
            // 跳过不可读的指令文件。
        }
        return null;
    }

    private Path resolveInstructionFilePath(String workDir, String filename) {
        if (isWindowsAbsolutePath(filename)) {
            return Path.of(filename);
        }
        Path filePath = Path.of(filename);
        if (filePath.isAbsolute()) {
            return filePath;
        }
        return Path.of(workDir, filename);
    }

    private boolean isWindowsAbsolutePath(String filename) {
        return filename.length() >= 3
            && Character.isLetter(filename.charAt(0))
            && filename.charAt(1) == ':'
            && (filename.charAt(2) == '\\' || filename.charAt(2) == '/');
    }

    /**
     * 自动发现并加载指令文件。
     *
     * <p>发现顺序：项目 AGENTS.md；若不存在则回退到项目 CLAUDE.md；然后 CONTEXT.md 和代理自定义文件。
     */
    private String buildInstructionFiles(String workDir, AgentDef agent) {
        LinkedHashSet<String> fileNames = new LinkedHashSet<>();

        // ── 项目级指令文件 ──
        Path agentsMd = Path.of(workDir, "AGENTS.md");
        Path claudeMd = Path.of(workDir, "CLAUDE.md");
        Path claudeMdAlt = Path.of(workDir, ".claude", "CLAUDE.md");
        if (Files.isRegularFile(agentsMd) && Files.isReadable(agentsMd)) {
            fileNames.add("AGENTS.md");
        } else if (Files.isRegularFile(claudeMd) && Files.isReadable(claudeMd)) {
            fileNames.add("CLAUDE.md");
        } else if (Files.isRegularFile(claudeMdAlt) && Files.isReadable(claudeMdAlt)) {
            fileNames.add(".claude/CLAUDE.md");
        }

        fileNames.add("CONTEXT.md");

        List<String> agentFiles = agent.getInstructionFiles();
        if (agentFiles != null) {
            fileNames.addAll(agentFiles);
        }

        // ── 构建段落并去重 ──
        String currentMessageId = null;
        StringBuilder sb = new StringBuilder();
        for (String fileName : fileNames) {
            if (currentMessageId != null && isClaimed(currentMessageId, fileName)) {
                continue;
            }
            String content = readInstructionFile(workDir, fileName);
            if (content != null && !content.isEmpty()) {
                if (currentMessageId != null) {
                    claim(currentMessageId, fileName);
                }
                sb.append("<instruction_file path=\"").append(fileName).append("\">\n");
                sb.append(content).append("\n");
                sb.append("</instruction_file>\n");
            }
        }

        String result = sb.toString().trim();
        return result.isEmpty() ? null : result;
    }

    /**
     * 构建 Git 上下文段。
     */
    private String buildGitContext(String workDir) {
        if (!isGitRepository(workDir)) {
            return null;
        }

        String branch = runGitCommand(workDir, "git", "rev-parse", "--abbrev-ref", "HEAD");
        String remote = runGitCommand(workDir, "git", "remote", "get-url", "origin");

        if (branch == null && remote == null) {
            return null;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("<git_context>\n");
        if (branch != null) {
            sb.append("Branch: ").append(branch).append("\n");
        }
        if (remote != null) {
            sb.append("Remote: ").append(remote).append("\n");
        }
        sb.append("</git_context>");
        return sb.toString();
    }

    /**
     * 在工作目录中执行 Git 命令，超时 5 秒。
     */
    private String runGitCommand(String workDir, String... command) {
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(Path.of(workDir).toFile());
            pb.redirectErrorStream(false);
            Process process = pb.start();

            boolean finished = process.waitFor(5, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return null;
            }

            if (process.exitValue() != 0) {
                return null;
            }

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String output = reader.lines().collect(Collectors.joining("\n")).trim();
                return output.isEmpty() ? null : output;
            }
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 判断工作目录是否位于 Git 仓库内。
     */
    private boolean isGitRepository(String workDir) {
        try {
            Path dir = Path.of(workDir);
            Path current = dir;
            while (current != null) {
                if (Files.isDirectory(current.resolve(".git"))) {
                    return true;
                }
                current = current.getParent();
            }
        } catch (Exception e) {
            // 忽略检测过程中的错误。
        }
        return false;
    }
}
