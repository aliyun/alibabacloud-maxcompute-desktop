package com.aliyun.odps.agentic.tool.schema;


import com.aliyun.odps.agentic.tool.ContextTool;
import com.aliyun.odps.agentic.tool.SkillParameter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;



import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/** Builds stable layered tool schemas using host-supplied profiles and definition types. */
public class ToolDefinitionFactory<D> {

    private static final Logger log = LoggerFactory.getLogger(ToolDefinitionFactory.class);

    private final java.util.function.Function<String,ContextTool<?,?>> tools;
    private final java.util.function.Function<ContextTool<?,?>,ToolSchemaProfile> profiles;
    private final DefinitionBuilder<D> definitions;
    private final boolean preferEnglish;

    private final Map<String, D> cache = new ConcurrentHashMap<>();

    // Metrics counters (Task 2.4)
    private final LongAdder cacheHit = new LongAdder();
    private final LongAdder cacheMiss = new LongAdder();
    private final LongAdder englishProfileCount = new LongAdder();
    private final LongAdder fallbackProfileCount = new LongAdder();

    @FunctionalInterface public interface DefinitionBuilder<D> {
        D build(String name,String description,Map<String,Object> parameters);
    }
    public ToolDefinitionFactory(java.util.function.Function<String,ContextTool<?,?>> tools,
                                 java.util.function.Function<ContextTool<?,?>,ToolSchemaProfile> profiles,
                                 DefinitionBuilder<D> definitions,boolean preferEnglish) {
        this.tools=tools; this.profiles=profiles; this.definitions=definitions; this.preferEnglish=preferEnglish;
    }
    /**
     * Build tool definitions for the given names at the requested schema level.
     * Missing / unknown names are silently skipped.
     */
    public List<D> build(List<String> toolNames, ToolSchemaLevel level) {
        return build(toolNames, level, null);
    }

    /**
     * Build with per-tool level overrides (e.g. aigc L0 stub vs L2 full). Overrides win
     * over the set-wide level; tools without an override keep {@code level}.
     */
    public List<D> build(List<String> toolNames, ToolSchemaLevel level,
                                                Map<String, ToolSchemaLevel> levelOverrides) {
        if (toolNames == null || toolNames.isEmpty()) return new ArrayList<>();
        ToolSchemaLevel effective = level == null ? ToolSchemaLevel.L1_COMPACT : level;
        List<D> out = new ArrayList<>(toolNames.size());
        for (String name : toolNames) {
            ToolSchemaLevel perTool = levelOverrides != null
                ? levelOverrides.getOrDefault(name, effective) : effective;
            D def = buildOne(name, perTool);
            if (def != null) out.add(def);
        }
        return out;
    }

    public D buildOne(String toolName, ToolSchemaLevel level) {
        if (toolName == null || toolName.isEmpty()) return null;
        if (level == null) level = ToolSchemaLevel.L1_COMPACT;
        ContextTool<?,?> skill = tools.apply(toolName);
        if (skill == null) return null;

        ToolSchemaProfile profile = profiles.apply(skill);
        int version = profile == null ? 0 : profile.getSchemaVersion();
        String cacheKey = toolName + "|" + level.name() + "|" + version + "|" + (preferEnglish ? "EN" : "CN");

        D cached = cache.get(cacheKey);
        if (cached != null) {
            cacheHit.increment();
            return cached;
        }
        cacheMiss.increment();

        D built;
        if (skill instanceof SchemaLevelAwareSkill levelAware) {
            // Tool whose schema CONTENT differs per level (e.g. aigc L0 stub vs L2 full):
            // the skill builds its own definition; cache key already includes the level.
            built = buildFromLevelAware(levelAware, level);
        } else if (profile != null && preferEnglish) {
            englishProfileCount.increment();
            built = buildFromProfile(skill, profile, level);
        } else {
            fallbackProfileCount.increment();
            built = buildFromRaw(skill, level);
        }

        if (built == null) return null;
        // putIfAbsent avoids thundering-herd duplicates; identity equality is
        // preserved for subsequent callers.
        D prior = cache.putIfAbsent(cacheKey, built);
        return prior != null ? prior : built;
    }

    @SuppressWarnings("unchecked")
    private D buildFromLevelAware(SchemaLevelAwareSkill skill, ToolSchemaLevel level) {
        Map<String, Object> raw = skill.toToolDefinition(level);
        Map<String, Object> function = raw == null ? null : (Map<String, Object>) raw.get("function");
        if (function == null) return null;
        String name = (String) function.get("name");
        if (name == null || name.isBlank()) {
            log.warn("[ToolDefinitionFactory] Level-aware skill returned function definition with missing name");
            return null;
        }
        return definitions.build(name,
            (String) function.get("description"),
            (Map<String, Object>) function.get("parameters"));
    }

    private D buildFromProfile(ContextTool<?,?> skill, ToolSchemaProfile profile, ToolSchemaLevel level) {
        String description = profile.descriptionFor(level);
        // LinkedHashMap: 保证 Jackson 序列化时字段顺序确定（DashScope 显式缓存要求 tool JSON 字节级一致）。
        // 顶层固定顺序: type → properties → required
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("type", "object");

        // properties 顺序按 SkillParameter 声明顺序
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        com.aliyun.odps.agentic.tool.ParameterSpec[] params = skill.getParameters();
        if (params != null) {
            for (com.aliyun.odps.agentic.tool.ParameterSpec p : params) {
                Map<String, Object> prop;
                if (p.getJsonSchema() != null) {
                    // 完整 JSON Schema 直接使用，仅补 description
                    prop = new LinkedHashMap<>(p.getJsonSchema());
                    if (level != ToolSchemaLevel.L0_MINIMAL && !prop.containsKey("description")) {
                        String desc = profile.paramDescriptionFor(p.getName(), level);
                        if (desc == null || desc.isEmpty()) desc = p.getDescription();
                        if (desc != null && !desc.isEmpty()) prop.put("description", desc);
                    }
                } else {
                    // 单个参数固定顺序: type → description → enum
                    prop = new LinkedHashMap<>();
                    prop.put("type", p.getType());
                    if (level != ToolSchemaLevel.L0_MINIMAL) {
                        String desc = profile.paramDescriptionFor(p.getName(), level);
                        if (desc == null || desc.isEmpty()) {
                            desc = p.getDescription();
                        }
                        if (desc != null && !desc.isEmpty()) {
                            prop.put("description", desc);
                        }
                    }
                    if (p.getEnumValues() != null && !p.getEnumValues().isEmpty()) {
                        prop.put("enum", p.getEnumValues());
                    }
                    // §17.2: emit JSON-schema maxLength for string params with constraint
                    if (p.getMaxLength() != null && "string".equals(p.getType())) {
                        prop.put("maxLength", p.getMaxLength());
                    }
                }
                properties.put(p.getName(), prop);
                if (p.isRequired()) required.add(p.getName());
            }
        }
        parameters.put("properties", properties);
        if (!required.isEmpty()) parameters.put("required", required);

        return definitions.build(skill.getName(), description, parameters);
    }

    private D buildFromRaw(ContextTool<?,?> skill, ToolSchemaLevel level) {
        // Use the Skill's own definition as the authoritative fallback, but
        // still honour L0_MINIMAL by stripping descriptions.
        Map<String, Object> raw = skill.toToolDefinition();
        @SuppressWarnings("unchecked")
        Map<String, Object> function = raw == null ? null : (Map<String, Object>) raw.get("function");
        if (function == null) return null;

        String name = (String) function.get("name");
        if (name == null || name.isBlank()) {
            log.warn("[ToolDefinitionFactory] Skill {} returned function definition with missing or empty name", skill.getName());
            return null;
        }
        String description = (String) function.get("description");
        @SuppressWarnings("unchecked")
        Map<String, Object> parameters = (Map<String, Object>) function.get("parameters");

        if (level == ToolSchemaLevel.L0_MINIMAL) {
            description = "";
            parameters = stripParamDescriptions(parameters);
        } else if (level == ToolSchemaLevel.L1_COMPACT && description != null) {
            description = ToolSchemaProfile.fallbackFromRaw(name, description).descriptionFor(ToolSchemaLevel.L1_COMPACT);
        }
        return definitions.build(name, description, parameters);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> stripParamDescriptions(Map<String, Object> parameters) {
        if (parameters == null) return null;
        // LinkedHashMap: 保留原 parameters 字段顺序，避免 description 剥离过程把顺序打乱。
        Map<String, Object> copy = new LinkedHashMap<>(parameters);
        Object props = copy.get("properties");
        if (props instanceof Map<?, ?>) {
            Map<String, Object> propertiesCopy = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : ((Map<String, Object>) props).entrySet()) {
                if (e.getValue() instanceof Map<?, ?>) {
                    Map<String, Object> pCopy = new LinkedHashMap<>((Map<String, Object>) e.getValue());
                    pCopy.remove("description");
                    propertiesCopy.put(e.getKey(), pCopy);
                } else {
                    propertiesCopy.put(e.getKey(), e.getValue());
                }
            }
            copy.put("properties", propertiesCopy);
        }
        return copy;
    }

    // === Metrics accessors (for TurnMetricsRecorder / tests) ===
    public long getCacheHitCount() { return cacheHit.sum(); }
    public long getCacheMissCount() { return cacheMiss.sum(); }
    public long getEnglishProfileCount() { return englishProfileCount.sum(); }
    public long getFallbackProfileCount() { return fallbackProfileCount.sum(); }

    public double getCacheHitRatio() {
        long hit = cacheHit.sum();
        long total = hit + cacheMiss.sum();
        return total == 0 ? 0.0 : (double) hit / (double) total;
    }

    public int getCacheSize() { return cache.size(); }

    /** Test / ops hook: clear the cache (e.g. after a hot-reload of skills). */
    public void invalidateAll() { cache.clear(); }
}
