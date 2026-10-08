package com.aliyun.odps.agentic.skill.manifest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 加载 Skill manifest YAML（来源：classpath {@code META-INF/skills/*.yml} 与 {@code ~/.maxquery/skills/*.yml}）。
 *
 * <p>使用 SafeConstructor，禁用任意类构造（防 YAML 反序列化攻击）。
 * 不做 Schema 校验（参见 {@link SkillManifestValidator}）。
 */
public class SkillManifestLoader {

    private static final Logger log = LoggerFactory.getLogger(SkillManifestLoader.class);

    /** classpath 内置 manifest 资源模式。 */
    public static final String CLASSPATH_PATTERN = "classpath*:META-INF/skills/*.yml";
    public static final String CLASSPATH_PATTERN_YAML = "classpath*:META-INF/skills/*.yaml";

    private final ManifestResources resolver;

    public SkillManifestLoader() {
        this(new ClasspathManifestResources());
    }

    public SkillManifestLoader(ManifestResources resolver) {
        this.resolver = resolver;
    }

    /** 加载 classpath 内置 manifest。 */
    public List<SkillManifest> loadBuiltinManifests() {
        List<SkillManifest> result = new ArrayList<>();
        for (String pattern : List.of(CLASSPATH_PATTERN, CLASSPATH_PATTERN_YAML)) {
            try {
                var resources = resolver.scan(pattern);
                for (ManifestResources.Resource resource : resources) {
                    try (InputStream is = resource.getInputStream()) {
                        SkillManifest manifest = parse(is);
                        if (manifest != null) {
                            manifest.setSource(SkillManifest.SOURCE_BUILTIN);
                            String url = safeUrl(resource);
                            manifest.setSourcePath(url);
                            result.add(manifest);
                        }
                    } catch (Exception e) {
                        log.warn("[SkillManifestLoader] Failed to parse builtin manifest {}: {}",
                            safeUrl(resource), e.getMessage());
                    }
                }
            } catch (IOException e) {
                log.warn("[SkillManifestLoader] Failed to scan {}: {}", pattern, e.getMessage());
            }
        }
        return result;
    }

    /** 加载用户目录 manifest（容忍目录不存在）。 */
    public List<SkillManifest> loadUserManifests(Path userDir) {
        List<SkillManifest> result = new ArrayList<>();
        if (userDir == null || !Files.exists(userDir) || !Files.isDirectory(userDir)) {
            return result;
        }
        try (Stream<Path> stream = Files.list(userDir)) {
            stream.filter(p -> {
                String n = p.getFileName().toString().toLowerCase();
                return n.endsWith(".yml") || n.endsWith(".yaml");
            }).forEach(p -> {
                try (InputStream is = Files.newInputStream(p)) {
                    SkillManifest manifest = parse(is);
                    if (manifest != null) {
                        manifest.setSource(SkillManifest.SOURCE_USER);
                        manifest.setSourcePath(p.toString());
                        result.add(manifest);
                    }
                } catch (Exception e) {
                    log.warn("[SkillManifestLoader] Failed to parse user manifest {}: {}",
                        p, e.getMessage());
                }
            });
        } catch (IOException e) {
            log.warn("[SkillManifestLoader] Failed to list user manifest dir {}: {}", userDir, e.getMessage());
        }
        return result;
    }

    /** 解析 YAML 输入流为 manifest（YAML 错误返回 null，不抛）。 */
    @SuppressWarnings("unchecked")
    public SkillManifest parse(InputStream is) {
        Yaml yaml = createSafeYaml();
        Object loaded;
        try {
            loaded = yaml.load(is);
        } catch (Exception e) {
            log.warn("[SkillManifestLoader] YAML parse error: {}", e.getMessage());
            return null;
        }
        if (!(loaded instanceof Map)) {
            return null;
        }
        Map<String, Object> map = (Map<String, Object>) loaded;
        return fromMap(map);
    }

    /** 从已解析的 Map 构造 manifest（公开供测试用）。 */
    @SuppressWarnings("unchecked")
    public SkillManifest fromMap(Map<String, Object> map) {
        if (map == null) return null;
        SkillManifest m = new SkillManifest();

        Object name = map.get("name");
        if (name != null) m.setName(String.valueOf(name));

        Object description = map.get("description");
        if (description != null) m.setDescription(String.valueOf(description));

        Object version = map.get("version");
        if (version != null) m.setVersion(String.valueOf(version));

        Object tags = map.get("tags");
        if (tags instanceof List) {
            List<String> tagList = new ArrayList<>();
            for (Object t : (List<?>) tags) {
                if (t != null) tagList.add(String.valueOf(t));
            }
            m.setTags(tagList);
        }

        Object caps = map.get("requiredCapabilities");
        if (caps instanceof List) {
            java.util.LinkedHashSet<String> capSet = new java.util.LinkedHashSet<>();
            for (Object c : (List<?>) caps) {
                if (c != null) capSet.add(String.valueOf(c));
            }
            m.setRequiredCapabilities(capSet);
        }

        Object sandbox = map.get("sandboxLevel");
        if (sandbox != null) m.setSandboxLevel(String.valueOf(sandbox));

        Object impl = map.get("implementation");
        if (impl != null) m.setImplementation(String.valueOf(impl));

        Object input = map.get("inputSchema");
        if (input instanceof Map) {
            m.setInputSchema(new LinkedHashMap<>((Map<String, Object>) input));
        }

        Object output = map.get("outputSchema");
        if (output instanceof Map) {
            m.setOutputSchema(new LinkedHashMap<>((Map<String, Object>) output));
        }

        Object enabled = map.get("enabled");
        if (enabled instanceof Boolean) {
            m.setEnabled((Boolean) enabled);
        }
        return m;
    }

    /**
     * Load Prompt Skills from SKILL.md files in a directory.
     * Discovery: each subdirectory containing SKILL.md becomes one skill.
     * Falls back to scanning root-level *.md files.
     */
    public List<SkillManifest> loadMarkdownSkills(Path dir, String source) {
        List<SkillManifest> result = new ArrayList<>();
        if (dir == null || !Files.exists(dir) || !Files.isDirectory(dir)) {
            return result;
        }
        try (Stream<Path> stream = Files.list(dir)) {
            stream.forEach(entry -> {
                if (Files.isDirectory(entry)) {
                    Path skillFile = entry.resolve("SKILL.md");
                    if (Files.exists(skillFile) && Files.isRegularFile(skillFile)) {
                        SkillManifest m = parseMarkdownSkill(skillFile, entry.getFileName().toString());
                        if (m != null) {
                            m.setSource(source);
                            m.setSourcePath(skillFile.toAbsolutePath().toString());
                            result.add(m);
                        }
                    }
                } else if (entry.getFileName().toString().toLowerCase().endsWith(".md")
                    && !entry.getFileName().toString().equalsIgnoreCase("README.md")) {
                    SkillManifest m = parseMarkdownSkill(entry, null);
                    if (m != null) {
                        m.setSource(source);
                        m.setSourcePath(entry.toAbsolutePath().toString());
                        result.add(m);
                    }
                }
            });
        } catch (IOException e) {
            log.warn("[SkillManifestLoader] Failed to scan markdown skills in {}: {}", dir, e.getMessage());
        }
        return result;
    }

    /**
     * Parse a SKILL.md file with YAML frontmatter (--- delimited).
     * Extracts name, description, disable-model-invocation from frontmatter.
     * Sets implementation to "prompt:{absolutePath}".
     */
    @SuppressWarnings("unchecked")
    public SkillManifest parseMarkdownSkill(Path mdFile, String fallbackName) {
        try {
            String content = Files.readString(mdFile);
            Map<String, Object> frontmatter = parseFrontmatter(content);

            SkillManifest m = new SkillManifest();
            String name = frontmatter != null ? (String) frontmatter.get("name") : null;
            if (name == null || name.isBlank()) {
                name = fallbackName != null ? fallbackName
                    : mdFile.getFileName().toString().replaceAll("\\.[mM][dD]$", "");
            }
            m.setName(name);

            String desc = frontmatter != null ? (String) frontmatter.get("description") : null;
            if (desc == null || desc.isBlank()) {
                // 根因修复：缺少 frontmatter/description 时不再把描述兜底成 name（会产出 LLM 无从触发的"死 skill"），
                // 改为从正文（H1 标题 + 首段）推导出可用描述，并告警提示作者补全 frontmatter。
                String derived = deriveDescriptionFromBody(content);
                if (frontmatter == null) {
                    log.warn("[SkillManifestLoader] SKILL.md 缺少 YAML frontmatter：{}，已从正文推导描述；建议补全 name/description frontmatter。", mdFile);
                } else {
                    log.warn("[SkillManifestLoader] SKILL.md frontmatter 缺少 description：{}，已从正文推导。", mdFile);
                }
                m.setDescription(derived != null ? derived : name);
            } else {
                m.setDescription(desc);
            }

            m.setImplementation("prompt:" + mdFile.toAbsolutePath());
            m.setSandboxLevel(SkillManifest.SANDBOX_USER);
            m.setEnabled(true);

            // 版本号
            Object version = frontmatter != null ? frontmatter.get("version") : null;
            if (version != null) m.setVersion(String.valueOf(version));

            // 标签
            Object tags = frontmatter != null ? frontmatter.get("tags") : null;
            if (tags instanceof List) {
                List<String> tagList = new ArrayList<>();
                for (Object t : (List<?>) tags) { if (t != null) tagList.add(String.valueOf(t)); }
                m.setTags(tagList);
            }

            // 依赖声明：requires (Tool Skills)
            Object requires = frontmatter != null ? frontmatter.get("requires") : null;
            if (requires instanceof List) {
                List<String> reqList = new ArrayList<>();
                for (Object r : (List<?>) requires) { if (r != null) reqList.add(String.valueOf(r)); }
                m.setRequires(reqList);
            }

            // 组合声明：composedWith (Prompt Skills)
            Object composedWith = frontmatter != null ? frontmatter.get("composedWith") : null;
            if (composedWith instanceof List) {
                List<String> compList = new ArrayList<>();
                for (Object c : (List<?>) composedWith) { if (c != null) compList.add(String.valueOf(c)); }
                m.setComposedWith(compList);
            }

            // 允许的工具子集
            Object allowedTools = frontmatter != null ? frontmatter.get("allowed-tools") : null;
            if (allowedTools != null) m.setAllowedTools(String.valueOf(allowedTools));

            Object disableInvocation = frontmatter != null ? frontmatter.get("disable-model-invocation") : null;
            if (Boolean.TRUE.equals(disableInvocation)) {
                m.setEnabled(false);
            }

            return m;
        } catch (IOException e) {
            log.warn("[SkillManifestLoader] Failed to read SKILL.md {}: {}", mdFile, e.getMessage());
            return null;
        }
    }

    /**
     * Parse YAML frontmatter from a markdown string (delimited by --- lines).
     * Returns null if no frontmatter found.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> parseFrontmatter(String content) {
        if (content == null || !startsWithFrontmatterFence(content)) return null;
        int end = content.indexOf("\n---", 3);
        if (end < 0) return null;
        String yamlBlock = content.substring(3, end).trim();
        if (yamlBlock.isEmpty()) return null;
        try {
            Yaml yaml = createSafeYaml();
            Object parsed = yaml.load(yamlBlock);
            if (parsed instanceof Map) {
                return (Map<String, Object>) parsed;
            }
        } catch (Exception e) {
            log.warn("[SkillManifestLoader] Frontmatter parse error: {}", e.getMessage());
        }
        return null;
    }

    /** 正文推导描述时的最大长度（避免把整段说明塞进 tool 描述）。 */
    private static final int DERIVED_DESC_MAX = 300;

    /**
     * 从 SKILL.md 正文推导一段可用描述（frontmatter 缺失/无 description 时的兜底）。
     *
     * <p>策略：取首个 H1 标题（去掉 {@code #}）作为标题；再取标题之后第一段非标题、非空的正文，
     * 合成 {@code "标题 — 首段"}。若无正文段落则仅用标题；若连标题都没有返回 null（交由调用方回退到 name）。
     * 结果截断到 {@link #DERIVED_DESC_MAX} 字符。
     */
    public static String deriveDescriptionFromBody(String content) {
        if (content == null || content.isBlank()) return null;
        // 仅在确为有效 frontmatter（--- 独占开栏 + 中间块可解析为非空 YAML Map）时才跳过；
        // 否则把开头的 --- 当作正文里的水平线/装饰，避免误吞真正的 H1 标题与首段（P2 根因修复：
        // 与 parseFrontmatter 统一判定，消除两处独立、精度不同的 frontmatter 检测器）。
        int bodyOffset = frontmatterBodyOffset(content);
        String body = bodyOffset > 0 ? content.substring(bodyOffset) : content;
        String title = null;
        StringBuilder para = new StringBuilder();
        for (String raw : body.split("\n")) {
            String line = raw.strip();
            if (line.isEmpty()) {
                if (para.length() > 0) break; // 首段结束
                continue;
            }
            if (isThematicBreak(line)) {
                // markdown 水平线（---/***/___）当作分隔：首段前的装饰线跳过，首段后视为结束。
                if (para.length() > 0) break;
                continue;
            }
            if (line.startsWith("#")) {
                if (title == null) {
                    title = line.replaceFirst("^#+\\s*", "").strip();
                } else if (para.length() == 0) {
                    // 标题后紧跟另一个标题：继续找正文段落
                    continue;
                } else {
                    break;
                }
                continue;
            }
            // 普通正文行（仅在已见过至少一个标题后，或压根没标题时采集首段）
            if (para.length() > 0) para.append(' ');
            para.append(line);
        }
        String summary = para.toString().strip();
        String result;
        if (title != null && !summary.isEmpty()) {
            result = title + " — " + summary;
        } else if (title != null) {
            result = title;
        } else if (!summary.isEmpty()) {
            result = summary;
        } else {
            return null;
        }
        if (result.length() > DERIVED_DESC_MAX) {
            result = result.substring(0, DERIVED_DESC_MAX).strip() + "…";
        }
        return result; // 到此 result 必非 null（前面 else 分支已对全空返回 null）
    }

    /** 是否为 markdown 主题分隔线（thematic break）：仅由 3+ 个同种 {@code -}/{@code *}/{@code _} 组成（允许空白）。 */
    private static boolean isThematicBreak(String line) {
        String s = line.replace(" ", "").replace("\t", "");
        if (s.length() < 3) return false;
        return s.chars().allMatch(c -> c == '-')
            || s.chars().allMatch(c -> c == '*')
            || s.chars().allMatch(c -> c == '_');
    }

    /** 开头的 {@code ---} 是否为 frontmatter 开栏（独占一行）——排除 {@code ----} 水平线与 {@code "--- 文本"}。 */
    private static boolean startsWithFrontmatterFence(String content) {
        if (content == null || !content.startsWith("---")) return false;
        if (content.length() == 3) return true;
        char c = content.charAt(3);
        return c == '\n' || c == '\r';
    }

    /**
     * 返回有效 YAML frontmatter 块结束后的正文起始偏移；无有效 frontmatter 返回 0。
     *
     * <p>判定与 {@link #parseFrontmatter} 完全一致：{@code ---} 必须独占开栏、有配对 {@code \n---}、
     * 且中间块能解析为<b>非空 YAML Map</b>——从而把"正文开头的水平线/装饰"与真正的 frontmatter 区分开，
     * 避免误吞真正的 H1 标题与首段。
     */
    private static int frontmatterBodyOffset(String content) {
        if (!startsWithFrontmatterFence(content)) return 0;
        int end = content.indexOf("\n---", 3);
        if (end < 0) return 0;
        String block = content.substring(3, end).trim();
        if (block.isEmpty()) return 0;
        try {
            Object parsed = createSafeYaml().load(block);
            if (!(parsed instanceof Map) || ((Map<?, ?>) parsed).isEmpty()) return 0;
        } catch (Exception e) {
            return 0;
        }
        int nl = content.indexOf('\n', end + 1);
        return nl >= 0 ? nl + 1 : content.length();
    }

    private static Yaml createSafeYaml() {
        LoaderOptions options = new LoaderOptions();
        options.setMaxAliasesForCollections(50);
        options.setAllowDuplicateKeys(false);
        return new Yaml(new SafeConstructor(options));
    }

    private static String safeUrl(ManifestResources.Resource resource) { return resource.sourcePath(); }

    /** 已知合法的 sandbox 等级集合（暴露给上层做白名单）。 */
    public static Set<String> allowedSandboxLevels() {
        return Set.of(
            SkillManifest.SANDBOX_BUILTIN,
            SkillManifest.SANDBOX_USER,
            SkillManifest.SANDBOX_EXTERNAL
        );
    }
}
