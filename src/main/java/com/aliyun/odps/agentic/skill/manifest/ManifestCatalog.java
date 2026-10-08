package com.aliyun.odps.agentic.skill.manifest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Manifest 驱动的 Skills 加载器（替代/包装 the host tool registry 的硬编码注册）。
 *
 * <p>职责：
 * <ol>
 *   <li>启动时从 classpath {@code META-INF/skills/*.yml} 加载内置 manifest，
 *       并扫描构造参数指定的用户目录 加载用户 manifest。</li>
 *   <li>调用 {@link SkillManifestValidator} 校验，校验失败的 manifest 不加载，
 *       不影响已加载的 manifest（红线 #13 的隔离原则）。</li>
 *   <li>对外提供 {@link #findManifest(String)} / {@link #listManifests()} / {@link #reload()}。</li>
 *   <li>触发 SSE 事件 {@code skill_registered} / {@code skill_unloaded}。</li>
 * </ol>
 *
 * <p>设计要点：
 * <ul>
 *   <li>本服务<b>只</b>管理 manifest 元数据（governance / metadata layer）；运行时 Skill 实例
 *       仍由上下文工具注册器持有。</li>
 *   <li>manifest 与 SkillRegistry 通过 skill name 关联；启动后由 the host registry adapter
 *       完成 cross-link。</li>
 *   <li>用户目录的 manifest 仅作为元数据可见层（暴露给前端管理页），运行时 Skill 实例
 *       保留为 builtin（防止 sandbox escalation）。</li>
 * </ul>
 */
public class ManifestCatalog {

    private static final Logger log = LoggerFactory.getLogger(ManifestCatalog.class);

    public static final String EVENT_SKILL_REGISTERED = "skill_registered";
    public static final String EVENT_SKILL_UNLOADED = "skill_unloaded";
    public static final String EVENT_SKILL_RELOADED = "skill_reloaded";

    private final SkillManifestLoader loader;
    private final SkillManifestValidator validator;

    /** 当前生效的 manifest（按 name 索引，保留插入顺序便于前端渲染）。 */
    private final Map<String, SkillManifest> manifests = new LinkedHashMap<>();
    /** per-session 启用/禁用覆盖：sessionId -> (skillName -> enabled)。 */
    private final Map<String, Map<String, Boolean>> sessionOverrides = new ConcurrentHashMap<>();
    /** SSE 事件订阅者。 */
    private final List<Consumer<Map<String, Object>>> eventListeners = new ArrayList<>();

    /** 构造参数指定的用户 manifest 目录。 */
    private final Path userSkillsDir;
    private Path legacySkillsDir;

    /** 持久化 disabled skill 列表的文件路径。 */
    private final Path visibilityConfigFile;

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 测试构造器：注入临时目录。 */
    public ManifestCatalog(SkillManifestLoader loader, SkillManifestValidator validator, Path userDir) {
        this.loader = loader;
        this.validator = validator;
        this.userSkillsDir = userDir;
        this.visibilityConfigFile = userDir.resolve("skills-visibility.json");
    }

    public void initialize() {
        ensureUserDir();
        migrateFromLegacyDir();
        doLoad(true);
        log.info("[SkillsLoader] Initialized: {} builtin + {} user manifests loaded (dir={})",
            manifests.values().stream().filter(m -> SkillManifest.SOURCE_BUILTIN.equals(m.getSource())).count(),
            manifests.values().stream().filter(m -> SkillManifest.SOURCE_USER.equals(m.getSource())).count(),
            userSkillsDir);
    }

    /**
     * 一次性迁移：如果旧目录 ~/.maxquery/skills/ 存在且 workspace/skills/ 为空，
     * 将旧目录内容复制过来。
     */
    private void migrateFromLegacyDir() {
        Path legacyDir = legacySkillsDir;
        if (legacyDir == null) return;
        if (legacyDir.equals(userSkillsDir)) return; // 未迁移，仍使用旧路径
        if (!Files.exists(legacyDir) || !Files.isDirectory(legacyDir)) return;
        try {
            // 只在 workspace/skills 为空时迁移
            boolean workspaceEmpty;
            try (var stream = Files.list(userSkillsDir)) {
                workspaceEmpty = stream.findFirst().isEmpty();
            }
            if (!workspaceEmpty) return;

            try (var stream = Files.walk(legacyDir)) {
                stream.forEach(source -> {
                    Path target = userSkillsDir.resolve(legacyDir.relativize(source));
                    try {
                        if (Files.isDirectory(source)) {
                            Files.createDirectories(target);
                        } else {
                            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
                        }
                    } catch (IOException e) {
                        log.warn("[SkillsLoader] Migration copy failed: {} -> {}: {}", source, target, e.getMessage());
                    }
                });
            }
            log.info("[SkillsLoader] Migrated legacy skills from {} to {}", legacyDir, userSkillsDir);
        } catch (IOException e) {
            log.warn("[SkillsLoader] Legacy dir migration failed: {}", e.getMessage());
        }
    }

    private void ensureUserDir() {
        if (userSkillsDir == null) return;
        try {
            Files.createDirectories(userSkillsDir);
        } catch (IOException e) {
            log.warn("[SkillsLoader] Failed to create user skills dir {}: {}", userSkillsDir, e.getMessage());
        }
    }

    /**
     * 重新加载所有 manifest（builtin + user）。
     * <p>
     * 校验失败的 manifest 被丢弃，不影响其他 manifest 的加载结果（红线 #13）。
     *
     * @return 本次加载的 manifest 数量
     */
    public synchronized int reload() {
        return doLoad(false);
    }

    private int doLoad(boolean isInitial) {
        Map<String, SkillManifest> previous = new LinkedHashMap<>(manifests);
        Map<String, SkillManifest> next = new LinkedHashMap<>();

        // 1. 内置（classpath）
        List<SkillManifest> builtins = loader.loadBuiltinManifests();
        for (SkillManifest m : builtins) {
            applyValidation(m, next);
        }

        // 2. 用户目录（YAML manifests）
        List<SkillManifest> userManifests = loader.loadUserManifests(userSkillsDir);
        for (SkillManifest m : userManifests) {
            applyValidation(m, next);
        }

        // 3. Prompt Skills（SKILL.md 文件 — 非 tool，注入 system prompt 目录）
        List<SkillManifest> promptSkills = loader.loadMarkdownSkills(userSkillsDir, SkillManifest.SOURCE_USER);
        for (SkillManifest m : promptSkills) {
            applyValidation(m, next);
        }

        synchronized (manifests) {
            manifests.clear();
            manifests.putAll(next);
        }

        restoreVisibilityState();

        if (!isInitial) {
            // 触发 SSE：新增、删除事件
            for (Map.Entry<String, SkillManifest> entry : next.entrySet()) {
                if (!previous.containsKey(entry.getKey())) {
                    emit(EVENT_SKILL_REGISTERED, entry.getValue());
                }
            }
            for (Map.Entry<String, SkillManifest> entry : previous.entrySet()) {
                if (!next.containsKey(entry.getKey())) {
                    emit(EVENT_SKILL_UNLOADED, entry.getValue());
                }
            }
            // 总览 reload 事件
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("type", EVENT_SKILL_RELOADED);
            payload.put("total", next.size());
            payload.put("ts", System.currentTimeMillis());
            broadcastEvent(payload);
        }

        log.info("[SkillsLoader] Loaded manifests: total={}, builtin={}, user={}",
            next.size(),
            next.values().stream().filter(m -> SkillManifest.SOURCE_BUILTIN.equals(m.getSource())).count(),
            next.values().stream().filter(m -> SkillManifest.SOURCE_USER.equals(m.getSource())).count());
        return next.size();
    }

    private void applyValidation(SkillManifest m, Map<String, SkillManifest> target) {
        if (m == null) return;
        SkillManifestValidator.Result result = validator.validate(m);
        if (!result.isValid()) {
            log.warn("[SkillsLoader] Reject manifest '{}' (source={}, path={}): errors={}",
                m.getName(), m.getSource(), m.getSourcePath(), result.getErrors());
            return;
        }
        if (!result.getWarnings().isEmpty()) {
            log.warn("[SkillsLoader] Manifest '{}' warnings: {}", m.getName(), result.getWarnings());
        }
        // 重复名：内置优先（builtin 来源不会被 user 来源覆盖；同来源后到覆盖前者）
        SkillManifest existing = target.get(m.getName());
        if (existing != null) {
            if (SkillManifest.SOURCE_BUILTIN.equals(existing.getSource())
                && SkillManifest.SOURCE_USER.equals(m.getSource())) {
                log.warn("[SkillsLoader] Skip user manifest '{}' (path={}): conflicts with builtin",
                    m.getName(), m.getSourcePath());
                return;
            }
            log.info("[SkillsLoader] Manifest '{}' overridden by {}", m.getName(), m.getSourcePath());
        }
        target.put(m.getName(), m);
    }

    /** 查找指定名称的 manifest（不存在返回 null）。 */
    public SkillManifest findManifest(String name) {
        synchronized (manifests) {
            return manifests.get(name);
        }
    }

    /** 列出所有 manifest（拷贝，不可变）。 */
    public List<SkillManifest> listManifests() {
        synchronized (manifests) {
            return new ArrayList<>(manifests.values());
        }
    }

    /** 当前已加载 manifest 数量。 */
    public int size() {
        synchronized (manifests) {
            return manifests.size();
        }
    }

    /** 设置 per-session 启用/禁用覆盖。 */
    public void setSessionEnabled(String sessionId, String skillName, boolean enabled) {
        if (sessionId == null || skillName == null) return;
        sessionOverrides
            .computeIfAbsent(sessionId, k -> new ConcurrentHashMap<>())
            .put(skillName, enabled);
    }

    /** 查询某个 session 中 skill 是否启用（若无 session 覆盖则取 manifest.enabled）。 */
    public boolean isEnabledForSession(String sessionId, String skillName) {
        Map<String, Boolean> overrides = sessionOverrides.get(sessionId);
        if (overrides != null && overrides.containsKey(skillName)) {
            return overrides.get(skillName);
        }
        SkillManifest m = findManifest(skillName);
        return m == null || m.isEnabled();
    }

    /** 全局启用/禁用（写入 manifest.enabled + 持久化到磁盘）。 */
    public void setEnabled(String skillName, boolean enabled) {
        SkillManifest m = findManifest(skillName);
        if (m != null) {
            m.setEnabled(enabled);
            persistVisibilityState();
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("type", enabled ? EVENT_SKILL_REGISTERED : EVENT_SKILL_UNLOADED);
            payload.put("name", skillName);
            payload.put("enabled", enabled);
            payload.put("ts", System.currentTimeMillis());
            broadcastEvent(payload);
        }
    }

    /** 返回该 session 中被显式 activate 的 skill name 集合。 */
    public Set<String> getSessionActivatedSkillNames(String sessionId) {
        if (sessionId == null) return Collections.emptySet();
        Map<String, Boolean> overrides = sessionOverrides.get(sessionId);
        if (overrides == null) return Collections.emptySet();
        return overrides.entrySet().stream()
            .filter(Map.Entry::getValue)
            .map(Map.Entry::getKey)
            .collect(Collectors.toSet());
    }

    /** 清理 session 覆盖（session 退出时调用）。 */
    public void clearSession(String sessionId) {
        sessionOverrides.remove(sessionId);
    }

    /** 订阅 SSE 事件（弱引用语义由调用方管理）。 */
    public void addEventListener(Consumer<Map<String, Object>> listener) {
        synchronized (eventListeners) {
            eventListeners.add(listener);
        }
    }

    /** 取消订阅。 */
    public void removeEventListener(Consumer<Map<String, Object>> listener) {
        synchronized (eventListeners) {
            eventListeners.remove(listener);
        }
    }

    /** 列出所有 Prompt Skills（implementation 以 prompt: 开头的 manifest）。 */
    public List<SkillManifest> listPromptSkills() {
        synchronized (manifests) {
            return manifests.values().stream()
                .filter(SkillManifest::isPromptSkill)
                .toList();
        }
    }

    /** 用户 manifest 目录（供测试和管理页查询）。 */
    public Path getUserSkillsDir() {
        return userSkillsDir;
    }

    private void emit(String type, SkillManifest manifest) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", type);
        payload.put("name", manifest.getName());
        payload.put("source", manifest.getSource());
        payload.put("sandboxLevel", manifest.getSandboxLevel());
        payload.put("ts", System.currentTimeMillis());
        broadcastEvent(payload);
    }

    private void broadcastEvent(Map<String, Object> payload) {
        Collection<Consumer<Map<String, Object>>> snapshot;
        synchronized (eventListeners) {
            snapshot = new ArrayList<>(eventListeners);
        }
        for (Consumer<Map<String, Object>> listener : snapshot) {
            try {
                listener.accept(payload);
            } catch (Exception e) {
                log.warn("[SkillsLoader] event listener error: {}", e.getMessage());
            }
        }
    }

    // ============================================================
    // Visibility 持久化
    // ============================================================

    /** 从磁盘加载 disabled skill 列表，应用到当前 manifests。 */
    private void restoreVisibilityState() {
        if (!Files.exists(visibilityConfigFile)) return;
        try {
            Map<String, Object> config = JSON.readValue(visibilityConfigFile.toFile(),
                new TypeReference<Map<String, Object>>() {});
            Object raw = config.get("disabledSkills");
            if (raw instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof String name) {
                        SkillManifest m = manifests.get(name);
                        if (m != null) m.setEnabled(false);
                    }
                }
            }
        } catch (IOException e) {
            log.warn("[SkillsLoader] Failed to read visibility config {}: {}", visibilityConfigFile, e.getMessage());
        }
    }

    /** 把当前 disabled skill 列表写入磁盘。 */
    private void persistVisibilityState() {
        try {
            List<String> disabled;
            synchronized (manifests) {
                disabled = manifests.values().stream()
                    .filter(m -> !m.isEnabled())
                    .map(SkillManifest::getName)
                    .toList();
            }
            Map<String, Object> config = new LinkedHashMap<>();
            config.put("disabledSkills", disabled);
            Files.createDirectories(visibilityConfigFile.getParent());
            JSON.writerWithDefaultPrettyPrinter().writeValue(visibilityConfigFile.toFile(), config);
        } catch (IOException e) {
            log.warn("[SkillsLoader] Failed to persist visibility config: {}", e.getMessage());
        }
    }

    /**
     * 与 the host tool registry 进行 cross-link：
     * 把 manifest 的 description / capabilities / sandbox 暴露给 registry，
     * 同时把 registry 中已有 skill 注册到 manifest 中（缺失则自动从 ToolSkill / Class 实例化）。
     *
     * <p>当前 MVP：只把 manifest 注入 registry 的元数据视图（registry 仍由 ToolSkillRegistrar 持有 skill 实例）。
     */
    public void setLegacySkillsDirectory(Path legacy) { this.legacySkillsDir=legacy; }
}
