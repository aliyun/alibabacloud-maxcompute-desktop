package com.aliyun.odps.agentic.skill.manifest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Skill manifest 校验器。
 * <p>
 * 负责两类校验：
 * <ol>
 *   <li><b>Schema 校验</b>：必填字段、字段格式、合法值。</li>
 *   <li><b>沙箱校验（红线 #13）</b>：用户来源 manifest 不得使用 {@code sandboxLevel: builtin}，
 *       禁止 escalation 攻击；网络/Shell 关键字白名单告警。</li>
 * </ol>
 *
 * <p>校验失败时返回 {@link Result}，{@link #validate} 不抛异常，调用方决定继续/拒绝。
 */
public class SkillManifestValidator {

    private static final Logger log = LoggerFactory.getLogger(SkillManifestValidator.class);

    private static final Set<String> ALLOWED_SANDBOX = Set.of(
        SkillManifest.SANDBOX_BUILTIN,
        SkillManifest.SANDBOX_USER,
        SkillManifest.SANDBOX_EXTERNAL
    );

    /** 用户 sandbox 描述中出现这些关键字时记 warn，不阻断（建议限制能力）。 */
    private static final Set<String> SUSPICIOUS_KEYWORDS = Set.of(
        "Runtime.exec", "ProcessBuilder", "java.net.URL",
        "HttpClient", "Shell", "/bin/", "powershell"
    );

    /**
     * 校验 manifest。
     * @param manifest 待校验对象（已加载、source 已填充）。
     * @return {@link Result#ok()} 表示通过；否则携带错误列表。
     */
    public Result validate(SkillManifest manifest) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        if (manifest == null) {
            errors.add("manifest is null");
            return new Result(false, errors, warnings);
        }

        // 1. 必填字段
        if (isBlank(manifest.getName())) {
            errors.add("missing required field: name");
        }
        if (isBlank(manifest.getDescription())) {
            errors.add("missing required field: description");
        }
        if (isBlank(manifest.getImplementation())) {
            errors.add("missing required field: implementation");
        }

        // 2. name 格式
        if (manifest.getName() != null && !manifest.getName().matches("[A-Za-z][A-Za-z0-9_-]*")) {
            errors.add("name must match [A-Za-z][A-Za-z0-9_-]*: " + manifest.getName());
        }

        // 3. sandboxLevel 合法值
        String sandbox = manifest.getSandboxLevel();
        if (sandbox == null || !ALLOWED_SANDBOX.contains(sandbox)) {
            errors.add("invalid sandboxLevel: " + sandbox + " (allowed: " + ALLOWED_SANDBOX + ")");
        }

        // 4. 红线 #13：sandbox escalation
        if (SkillManifest.SOURCE_USER.equals(manifest.getSource())
            && SkillManifest.SANDBOX_BUILTIN.equals(sandbox)) {
            errors.add("sandbox escalation rejected: user-source manifest cannot use sandboxLevel=builtin");
        }

        // 5. inputSchema 基础结构检查
        Map<String, Object> input = manifest.getInputSchema();
        if (input != null) {
            Object type = input.get("type");
            if (type != null && !"object".equals(type)) {
                warnings.add("inputSchema.type should be 'object' for tool schemas, got: " + type);
            }
            Object props = input.get("properties");
            if (props != null && !(props instanceof Map)) {
                errors.add("inputSchema.properties must be a map");
            }
        }

        // 6. outputSchema 基础结构（宽松）
        Map<String, Object> output = manifest.getOutputSchema();
        if (output != null) {
            Object type = output.get("type");
            if (type != null && !(type instanceof String)) {
                warnings.add("outputSchema.type should be a string");
            }
        }

        // 7. user sandbox 关键字告警
        if (SkillManifest.SANDBOX_USER.equals(sandbox) && manifest.getDescription() != null) {
            String desc = manifest.getDescription();
            for (String keyword : SUSPICIOUS_KEYWORDS) {
                if (desc.contains(keyword)) {
                    warnings.add("user-sandbox manifest contains suspicious keyword '" + keyword + "' in description");
                }
            }
        }

        boolean ok = errors.isEmpty();
        if (!ok && log.isWarnEnabled()) {
            log.warn("[SkillManifestValidator] Manifest '{}' (source={}) failed validation: {}",
                manifest.getName(), manifest.getSource(), errors);
        }
        return new Result(ok, errors, warnings);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /** 校验结果。 */
    public static final class Result {
        private final boolean valid;
        private final List<String> errors;
        private final List<String> warnings;

        public Result(boolean valid, List<String> errors, List<String> warnings) {
            this.valid = valid;
            this.errors = errors == null ? List.of() : List.copyOf(errors);
            this.warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }

        public static Result ok() {
            return new Result(true, List.of(), List.of());
        }

        public boolean isValid() { return valid; }
        public List<String> getErrors() { return errors; }
        public List<String> getWarnings() { return warnings; }

        @Override
        public String toString() {
            return "Result{valid=" + valid + ", errors=" + errors + ", warnings=" + warnings + "}";
        }
    }
}
