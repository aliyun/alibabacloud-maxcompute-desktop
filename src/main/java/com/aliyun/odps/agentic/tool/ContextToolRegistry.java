package com.aliyun.odps.agentic.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;

/**
 * Skill 注册中心
 * 管理所有可用的 Skills
 *
 * <p>执行注册工具时统一应用超时、输出限制和执行历史策略。
 * 宿主通过受保护的扩展方法提供开关、隔离级别和执行上下文。
 */
public class ContextToolRegistry<C,R extends InvocationResult,T extends ContextTool<C,R>> implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ContextToolRegistry.class);

    private static final long DEFAULT_SKILL_TIMEOUT_MS = 60_000;
    private static final long USER_SKILL_TIMEOUT_MS = 30_000;
    private static final long EXTERNAL_SKILL_TIMEOUT_MS = 15_000;
    /** Maximum output size for EXTERNAL sandbox (50 KB). */
    private static final int EXTERNAL_OUTPUT_LIMIT_BYTES = 50 * 1024;
    private static final int MAX_EXECUTION_HISTORY = 50;

    /**
     * Sandbox isolation level for skill execution.
     * <ul>
     *   <li>{@code BUILTIN} — trusted internal skills; 60s timeout, no extra isolation</li>
     *   <li>{@code USER} — user-provided skills; 30s timeout + OOM guard</li>
     *   <li>{@code EXTERNAL} — third-party skills; 15s timeout + OOM guard + 50KB output limit</li>
     * </ul>
     */
    public enum SandboxLevel {
        BUILTIN(DEFAULT_SKILL_TIMEOUT_MS),
        USER(USER_SKILL_TIMEOUT_MS),
        EXTERNAL(EXTERNAL_SKILL_TIMEOUT_MS);

        private final long timeoutMs;

        SandboxLevel(long timeoutMs) {
            this.timeoutMs = timeoutMs;
        }

        public long getTimeoutMs() {
            return timeoutMs;
        }

        /** Parse from manifest string value, defaulting to USER for unknown values. */
        public static SandboxLevel fromManifestValue(String value) {
            if (value == null) return USER;
            return switch (value.toLowerCase()) {
                case "builtin" -> BUILTIN;
                case "external" -> EXTERNAL;
                default -> USER;
            };
        }
    }

    protected final Map<String, T> skills = new ConcurrentHashMap<>();
    /** 关联的 manifest 加载器（Phase 9）。可空：旧测试 / 直接 new SkillRegistry() 兼容。 */
    private final Deque<SkillExecutionRecord> executionHistory = new ConcurrentLinkedDeque<>();
    private final ExecutorService skillExecutor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "skill-executor");
        t.setDaemon(true);
        return t;
    });

    /** Called on the caller thread; the returned scope is opened/closed on the worker thread. */
    protected java.util.function.Supplier<AutoCloseable> captureExecutionScope() { return () -> () -> {}; }
    protected long executionTimeout(String name,C context,SandboxLevel sandbox) { return sandbox.getTimeoutMs(); }
    protected boolean requiresSandbox(String name,Map<String,Object> args) { return false; }
    @Override public void close() { skillExecutor.shutdownNow(); }

    /**
     * 注册 Skill
     */
    public void register(T skill) {
        skills.put(skill.getName(), skill);
        log.info("[SkillRegistry] Registered skill: {}", skill.getName());
    }

    /**
     * 获取 Skill
     */
    public T getSkill(String name) {
        return skills.get(name);
    }

    /**
     * 注销 Skill
     */
    public void unregister(String name) {
        if (skills.remove(name) != null) {
            log.info("[SkillRegistry] Unregistered skill: {}", name);
        }
    }

    /**
     * 检查 Skill 是否存在
     */
    public boolean hasSkill(String name) {
        return skills.containsKey(name);
    }

    /**
     * 获取所有可用的 Skill 名称（按名称排序，保证迭代顺序稳定）。
     *
     * <p>ConcurrentHashMap.keySet() 迭代顺序不确定；显式排序
     * 避免下游（ActiveToolSet、prompt tool list）因迭代顺序抖动
     * 而击穿 DashScope prefix cache。
     */
    public List<String> getAvailableSkills() {
        List<String> names = new ArrayList<>();
        for (String name : skills.keySet()) {
            if (isGloballyEnabled(name)) {
                names.add(name);
            }
        }
        names.sort(Comparator.naturalOrder());
        return names;
    }

    /**
     * 获取所有 Skill 定义（用于 LLM tools 参数）。
     * 已禁用的 skill 不会出现在 tools 列表中，不占 system prompt 上下文。
     *
     * <p>按 skill name 排序，保证 tool JSON 数组跨轮次字节级一致，
     * 命中 DashScope tools 数组的 prefix cache。
     */
    public List<Map<String, Object>> getSkillDefinitions() {
        List<String> sortedNames = new ArrayList<>();
        for (String name : skills.keySet()) {
            if (isGloballyEnabled(name)) {
                sortedNames.add(name);
            }
        }
        sortedNames.sort(Comparator.naturalOrder());
        List<Map<String, Object>> definitions = new ArrayList<>(sortedNames.size());
        for (String name : sortedNames) {
            T skill = skills.get(name);
            if (skill != null) {
                definitions.add(skill.toToolDefinition());
            }
        }
        return definitions;
    }

    /**
     * 全局启用检查：如果 SkillsLoader 标记某 skill 为 disabled，则全局不可用。
     * 没有 manifest 或 loader 不存在时默认启用。
     */
    protected boolean isGloballyEnabled(String name) { return true; }
    public SandboxLevel getSandboxLevel(String name) { return hasSkill(name) ? SandboxLevel.BUILTIN : SandboxLevel.USER; }

    /**
     * 执行 Skill（带沙箱隔离、超时保护和执行历史记录）
     */
    public Map<String, Object> executeSkill(String name, Map<String, Object> args, C context) {
        T skill = skills.get(name);
        if (skill == null) {
            return Map.of(
                "success", false,
                "error", "Skill not found: " + name
            );
        }
        if (!isGloballyEnabled(name)) {
            return Map.of(
                "success", false,
                "error", "Skill '" + name + "' 已被禁用，请在设置中启用后重试"
            );
        }

        // ── 协作式取消/超时(CancellationGuard,所有 skill 都要,很轻)──
        // 超时单一真源 = stepTimeoutMs(见 TimeoutBudget,M1);SandboxLevel.getTimeoutMs() 仅兜底。
        SandboxLevel sandbox = getSandboxLevel(name);
        long timeoutMs = executionTimeout(name,context,sandbox);
        Map<String, Object> cleanArgs = stripInternalPrefixArgs(args);

        // ── 真沙箱(隔离/边界/限额)判定,与超时正交(SandboxPolicy,M6)──
        // 只对 executeCommand / 文件类 / external skill 为真;数据/SQL/元数据 skill 可信,不进沙箱。
        boolean requiresSandbox = requiresSandbox(name,cleanArgs);

        long startMs = System.currentTimeMillis();
        try {
            if (log.isDebugEnabled()) {
                Map<String, String> argTypes = new LinkedHashMap<>();
                if (cleanArgs != null) {
                    for (Map.Entry<String, Object> entry : cleanArgs.entrySet()) {
                        Object value = entry.getValue();
                        String typeDesc = value == null ? "null" : value.getClass().getSimpleName();
                        if (value instanceof Map) {
                            typeDesc = "Map" + ((Map<?, ?>) value).keySet();
                        } else if (value instanceof List) {
                            typeDesc = "List[" + ((List<?>) value).size() + "]";
                        }
                        argTypes.put(entry.getKey(), typeDesc);
                    }
                }
                log.debug("[SkillRegistry] Executing skill: {} (sandbox={}) with argTypes: {}",
                    name, sandbox, argTypes);
            }
            log.info("[SkillRegistry] Executing skill: {} (sandbox={}, timeout={}ms) with args: {}",
                name, sandbox, timeoutMs, cleanArgs);

            // 并行归因修复：skill 实际运行在 skill-executor 线程池，而 PerActionContext 是
            // ThreadLocal（由 ActionExecutor 在 agent-tool-pool 线程 set）。不传播的话，
            // 并行 executeQuery 在 skill 线程读到 null → 回退共享 AgentContext 的
            // currentToolCallId（被最后写入者覆盖），导致 query_status/query_result_ready
            // 事件全部归因到同一个 toolCallId，前端多卡片 LogView/结果互相覆盖。
            // 此处在提交前于调用线程捕获，再在执行线程恢复并 finally 清理。
            var scope = captureExecutionScope();
            Future<R> future = skillExecutor.submit(() -> {
                try (var ignored = scope.get()) { return skill.execute(cleanArgs,context); }
            });
            R result;
            try {
                result = future.get(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (TimeoutException te) {
                future.cancel(true);
                long elapsed = System.currentTimeMillis() - startMs;
                recordExecution(name, elapsed, false, "timeout", false);
                log.error("[SkillRegistry] Skill {} (sandbox={}) timed out after {}ms",
                    name, sandbox, elapsed);
                return Map.of("success", false, "error",
                    "Skill execution timed out after " + elapsed + "ms (sandbox=" + sandbox + ")");
            } catch (InterruptedException ie) {
                // R1 根因补强（活体 E2E 暴露）：本方法运行在 agent-tool-pool 线程，而 skill 实际跑在
                // skillExecutor(skill-executor) 线程。上层取消穿透只中断了本线程阻塞的 future.get，
                // 若不 cancel(true) 内层 future，skill-executor 会继续在 LocalCommandExecutor.waitFor
                // 里等满 → 前台子进程（如 python3 脚本）沦为孤儿空跑。必须 cancel(true) 打断
                // skill-executor，使 LocalCommandExecutor 抛 InterruptedException 走 killTree 杀整棵进程树。
                future.cancel(true);
                Thread.currentThread().interrupt();
                long elapsed = System.currentTimeMillis() - startMs;
                recordExecution(name, elapsed, false, "interrupted", false);
                log.info("[SkillRegistry] Skill {} interrupted; cancelled inner future to kill child process tree ({}ms)",
                    name, elapsed);
                return Map.of("success", false, "error", "技能执行被中断（已中止子进程）");
            }

            // --- EXTERNAL sandbox: enforce output size limit ---
            boolean outputTruncated = false;
            if (sandbox == SandboxLevel.EXTERNAL) {
                Map<String, Object> resultMap = result.toMap();
                String serialized = resultMap.toString();
                if (serialized.length() > EXTERNAL_OUTPUT_LIMIT_BYTES) {
                    outputTruncated = true;
                    log.warn("[SkillRegistry] Skill {} output truncated: {}B > {}B limit",
                        name, serialized.length(), EXTERNAL_OUTPUT_LIMIT_BYTES);
                    long elapsed = System.currentTimeMillis() - startMs;
                    recordExecution(name, elapsed, result.isSuccess(), null, true);
                    Map<String, Object> truncated = new LinkedHashMap<>();
                    truncated.put("success", result.isSuccess());
                    truncated.put("message", result.getMessage());
                    truncated.put("warning", "Output truncated: exceeded " + EXTERNAL_OUTPUT_LIMIT_BYTES + "B sandbox limit");
                    truncated.put("outputTruncated", true);
                    return truncated;
                }
            }

            long elapsed = System.currentTimeMillis() - startMs;
            recordExecution(name, elapsed, result.isSuccess(), null, outputTruncated);
            return result.toMap();
        } catch (ClassCastException e) {
            long elapsed = System.currentTimeMillis() - startMs;
            recordExecution(name, elapsed, false, "ClassCastException", false);
            Map<String, String> argTypes = new LinkedHashMap<>();
            if (args != null) {
                for (Map.Entry<String, Object> entry : args.entrySet()) {
                    Object value = entry.getValue();
                    argTypes.put(entry.getKey(), value == null ? "null" : value.getClass().getName());
                }
            }
            log.error("[SkillRegistry] Skill {} ClassCastException with argTypes: {} - {}",
                name, argTypes, e.getMessage());
            return Map.of(
                "success", false,
                "error", "参数类型错误: " + e.getMessage() + "，参数类型: " + argTypes
            );
        } catch (ExecutionException e) {
            long elapsed = System.currentTimeMillis() - startMs;
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            // --- 真沙箱(SandboxPolicy):catch OOM wrapped in ExecutionException ---
            if (cause instanceof OutOfMemoryError && requiresSandbox) {
                recordExecution(name, elapsed, false, "OutOfMemoryError", false);
                log.error("[SkillRegistry] Skill {} (sandbox={}) caused OutOfMemoryError — isolated",
                    name, sandbox);
                return Map.of("success", false,
                    "error", "Skill execution ran out of memory (sandbox=" + sandbox + ")");
            }
            recordExecution(name, elapsed, false, cause.getClass().getSimpleName(), false);
            log.error("[SkillRegistry] Skill {} execution failed", name, cause);
            return Map.of("success", false, "error", cause.getMessage());
        } catch (OutOfMemoryError oom) {
            // --- 真沙箱(SandboxPolicy):direct OOM catch ---
            if (requiresSandbox) {
                long elapsed = System.currentTimeMillis() - startMs;
                recordExecution(name, elapsed, false, "OutOfMemoryError", false);
                log.error("[SkillRegistry] Skill {} (sandbox={}) caused OutOfMemoryError — isolated",
                    name, sandbox);
                return Map.of("success", false,
                    "error", "Skill execution ran out of memory (sandbox=" + sandbox + ")");
            }
            throw oom; // BUILTIN: let OOM propagate
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - startMs;
            recordExecution(name, elapsed, false, e.getClass().getSimpleName(), false);
            log.error("[SkillRegistry] Skill {} execution failed", name, e);
            return Map.of("success", false, "error", e.getMessage());
        }
    }

    /** Skill执行错误日志路径（由 SkillsLoader 设置）。 */
    private volatile java.nio.file.Path skillErrorLogPath;

    /** 设置 Skill 执行错误日志路径。 */
    public void setSkillErrorLogPath(java.nio.file.Path path) {
        this.skillErrorLogPath = path;
    }

    private void recordExecution(String skillName, long durationMs, boolean success,
                                 String errorType, boolean outputTruncated) {
        executionHistory.addFirst(new SkillExecutionRecord(
                skillName, System.currentTimeMillis(), durationMs, success, errorType, outputTruncated));
        while (executionHistory.size() > MAX_EXECUTION_HISTORY) {
            executionHistory.removeLast();
        }
        // 错误日志写入 workspace/skills/.logs/
        if (!success && skillErrorLogPath != null) {
            writeErrorToLog(skillName, durationMs, errorType);
        }
    }

    private void writeErrorToLog(String skillName, long durationMs, String errorType) {
        try {
            java.nio.file.Path logDir = skillErrorLogPath.getParent();
            if (logDir != null) java.nio.file.Files.createDirectories(logDir);
            String line = String.format(
                "{\"ts\":%d,\"skill\":\"%s\",\"error\":\"%s\",\"durationMs\":%d}%n",
                System.currentTimeMillis(),
                skillName.replace("\"", "\\\""),
                errorType != null ? errorType.replace("\"", "\\\"") : "unknown",
                durationMs
            );
            java.nio.file.Files.writeString(skillErrorLogPath, line,
                java.nio.charset.StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.APPEND);
            // 简单 rotate: 超过 200KB 时截断
            if (java.nio.file.Files.size(skillErrorLogPath) > 200 * 1024) {
                java.nio.file.Path rotated = skillErrorLogPath.resolveSibling("execution-errors.prev.jsonl");
                java.nio.file.Files.move(skillErrorLogPath, rotated, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            log.warn("[SkillRegistry] Failed to write error log: {}", e.getMessage());
        }
    }

    public List<SkillExecutionRecord> getExecutionHistory() {
        return new ArrayList<>(executionHistory);
    }

    /**
     * Strip _-prefixed internal fields from args before passing to skill execution.
     * - _planStepId is always dropped (pure dispatcher metadata).
     * - Other _X keys: if X doesn't already exist in args, add as X (LLM naming error recovery).
     */
    private static Map<String, Object> stripInternalPrefixArgs(Map<String, Object> args) {
        if (args == null || args.isEmpty()) return args;
        boolean hasPrefix = false;
        for (String key : args.keySet()) {
            if (key.startsWith("_")) { hasPrefix = true; break; }
        }
        if (!hasPrefix) return args;
        Map<String, Object> clean = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : args.entrySet()) {
            String key = entry.getKey();
            if (key.startsWith("_")) {
                if ("_planStepId".equals(key)) continue;
                String stripped = key.substring(1);
                if (!args.containsKey(stripped)) {
                    clean.put(stripped, entry.getValue());
                }
            } else {
                clean.put(key, entry.getValue());
            }
        }
        return clean;
    }

    /**
     * 注册内置 Skills
     */
    public static class SkillExecutionRecord {
        private final String skillName;
        private final long timestamp;
        private final long durationMs;
        private final boolean success;
        private final String errorType;
        private final boolean outputTruncated;

        public SkillExecutionRecord(String skillName, long timestamp, long durationMs,
                                    boolean success, String errorType, boolean outputTruncated) {
            this.skillName = skillName;
            this.timestamp = timestamp;
            this.durationMs = durationMs;
            this.success = success;
            this.errorType = errorType;
            this.outputTruncated = outputTruncated;
        }

        public String getSkillName() { return skillName; }
        public long getTimestamp() { return timestamp; }
        public long getDurationMs() { return durationMs; }
        public boolean isSuccess() { return success; }
        public String getErrorType() { return errorType; }
        public boolean isOutputTruncated() { return outputTruncated; }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("skillName", skillName);
            m.put("timestamp", timestamp);
            m.put("durationMs", durationMs);
            m.put("success", success);
            if (errorType != null) m.put("errorType", errorType);
            if (outputTruncated) m.put("outputTruncated", true);
            return m;
        }
    }
}
