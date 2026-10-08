package com.aliyun.odps.agentic.config;


/**
 * Agent Feature Flags 配置
 *
 * App 发布默认：
 * - Agent action 决策固定使用 provider 原生 tool calling
 * - merged decision 是主路径，复杂/恢复场景由主循环降级到 two-stage native
 */
public class AgentFeatureSettings {

    private RuntimeFlags runtime = new RuntimeFlags();
    private EvalFlags eval = new EvalFlags();
    private DebugFlags debug = new DebugFlags();
    private GuardrailFlags guardrail = new GuardrailFlags();
    private CodingFlags coding = new CodingFlags();
    private SemanticPackFlags semanticPack = new SemanticPackFlags();
    private ReflectionFlags reflection = new ReflectionFlags();

    // ==================== Runtime Flags ====================

    public RuntimeFlags getRuntime() { return runtime; }
    public void setRuntime(RuntimeFlags runtime) { this.runtime = runtime != null ? runtime : new RuntimeFlags(); }

    // ==================== Eval Flags ====================

    public EvalFlags getEval() { return eval; }
    public void setEval(EvalFlags eval) { this.eval = eval != null ? eval : new EvalFlags(); }

    // ==================== Debug Flags ====================

    public DebugFlags getDebug() { return debug; }
    public void setDebug(DebugFlags debug) { this.debug = debug != null ? debug : new DebugFlags(); }

    public boolean isSseRecordingEnabled() {
        return debug != null && debug.getSseRecording() != null && debug.getSseRecording().isEnabled();
    }

    // ==================== Guardrail Flags (Phase 2A correctness guards) ====================

    public GuardrailFlags getGuardrail() { return guardrail; }
    public void setGuardrail(GuardrailFlags guardrail) { this.guardrail = guardrail != null ? guardrail : new GuardrailFlags(); }

    public boolean isJoinFanoutGuardEnabled() {
        return guardrail != null && guardrail.isJoinFanoutGuardEnabled();
    }

    public boolean isPartitionEnforcementEnabled() {
        return guardrail != null && guardrail.isPartitionEnforcementEnabled();
    }

    public int getPartitionDefaultDays() {
        return guardrail != null ? guardrail.getPartitionDefaultDays() : GuardrailFlags.DEFAULT_PARTITION_DAYS;
    }

    // ==================== Coding Sub-Agent Flags ====================

    public CodingFlags getCoding() { return coding; }
    public void setCoding(CodingFlags coding) { this.coding = coding != null ? coding : new CodingFlags(); }

    public boolean isCodingEnabled() {
        return coding != null && coding.isEnabled();
    }

    // ==================== Semantic Pack Tool Flags ====================

    public SemanticPackFlags getSemanticPack() { return semanticPack; }
    public void setSemanticPack(SemanticPackFlags semanticPack) {
        this.semanticPack = semanticPack != null ? semanticPack : new SemanticPackFlags();
    }

    // ==================== Reflection Flags (P0-2 LLM 自省层) ====================

    public ReflectionFlags getReflection() { return reflection; }
    public void setReflection(ReflectionFlags reflection) {
        this.reflection = reflection != null ? reflection : new ReflectionFlags();
    }

    /** reminder 是否走 LLM 反思；flags 缺失时保守到关闭（降级回固定文本）。 */
    public boolean isRejectionReflectionEnabled() {
        return reflection != null && reflection.isRejectionWriterEnabled();
    }

    /** 普通会话 critic 的失败信号阀值；flags 缺失时返 0 = 不放宽。 */
    public int getCriticFailureSignalThreshold() {
        return reflection != null ? reflection.getCriticFailureSignalThreshold() : 0;
    }

    public boolean isSemanticPackToolEnabled() {
        return semanticPack != null && semanticPack.isEnabled();
    }

    // ==================== deepCopy ====================

    protected AgentFeatureSettings newCopy() { return new AgentFeatureSettings(); }

    public AgentFeatureSettings deepCopy() {
        AgentFeatureSettings copy = newCopy();

        RuntimeFlags cr = copy.runtime, gr = this.runtime;
        cr.setGroundingValidator(gr.getGroundingValidator());

        EvalFlags ce = copy.eval, ge = this.eval;
        ce.setAccuracyThreshold(ge.getAccuracyThreshold());
        ce.setLatencyThreshold(ge.getLatencyThreshold());
        ce.setTokenThreshold(ge.getTokenThreshold());
        ce.setSseThreshold(ge.getSseThreshold());

        GuardrailFlags cg = copy.guardrail, gg = this.guardrail;
        cg.setJoinFanoutGuardEnabled(gg.isJoinFanoutGuardEnabled());
        cg.setPartitionEnforcementEnabled(gg.isPartitionEnforcementEnabled());
        cg.setPartitionDefaultDays(gg.getPartitionDefaultDays());

        // D21 根因修复（2026-08-08）：deepCopy 曾漏拷 coding/debug 两组——FeatureFlagsMergeHelper 对有
        // session override 的会话调 deepCopy 后，coding kill-switch/阈值与 sse 录制配置全部回默认，
        // 运维改过的 agent.coding.* 在该会话静默失效。新增 flag 组时必须同步本方法。
        CodingFlags cc = copy.coding, gc = this.coding;
        cc.setEnabled(gc.isEnabled());
        cc.setMaxConcurrentJobs(gc.getMaxConcurrentJobs());
        cc.setMaxGlobalJobs(gc.getMaxGlobalJobs());
        cc.setMaxDepth(gc.getMaxDepth());
        cc.setDoomLoopThreshold(gc.getDoomLoopThreshold());
        cc.setAutoResume(gc.isAutoResume());

        SemanticPackFlags cp = copy.semanticPack, gp = this.semanticPack;
        cp.setEnabled(gp.isEnabled());

        SseRecordingFlags cs = copy.debug.getSseRecording(), gs = this.debug.getSseRecording();
        cs.setEnabled(gs.isEnabled());
        cs.setOutputDir(gs.getOutputDir());

        return copy;
    }

    // ==================== 内部类定义 ====================

    public static class RuntimeFlags {
        private boolean groundingValidator = false;

        public boolean getGroundingValidator() { return groundingValidator; }
        public void setGroundingValidator(boolean groundingValidator) { this.groundingValidator = groundingValidator; }
    }

    /**
     * Coding 子 Agent 开关与阈值。前缀 {@code agent.coding.*}。
     *
     * <p>{@code enabled} 是 kill-switch（默认开）：关闭后 {@code code} 工具不进 tools 列表，
     * 主流程降级为不委派、不崩。其余为并发/数据通道/超时阈值（设计 §4.9/§4.11）。
     */
    public static class CodingFlags {
        private boolean enabled = true;
        /** 每父会话并发 job 上限。 */
        private int maxConcurrentJobs = 3;
        /** 全局并发 job 上限。 */
        private int maxGlobalJobs = 8;
        /** 委派深度上限（child 不得再委派 code；maxDepth=1 = 只允许一层）。 */
        private int maxDepth = 1;
        /** 同 kind+同 goal 连续委派次数达此阈值 → 转 AWAITING_CONFIRM（doom_loop 护栏）。 */
        private int doomLoopThreshold = 3;
        /**
         * A1 autoResume（默认开）：child job 抵非取消终态且父会话空闲时，主动续跑父的一个回合，
         * 让父经既有 consumeOutcomes 消费子结果，无需用户再发消息（补齐「结果已持久化 → 结果被消费」
         * 在父无下一轮时的缺失状态边）。关闭后回退为被动——父下一轮/下一条用户消息才消费。
         */
        private boolean autoResume = true;
        /**
         * 已了结 job 的**事件流**保留期（天，默认 7）。超期即清理其 {@code coding_job_events}
         * （job 行永远保留——它是 child 产物目录的归属记录，见
         * {@code CodingJobRepository.pruneEventsBefore}）。{@code 0} = 关闭清理（DELETE 不可逆，
         * 留一个关得掉的开关）。
         */
        private int eventRetentionDays = 7;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public int getMaxConcurrentJobs() { return maxConcurrentJobs; }
        public void setMaxConcurrentJobs(int v) { this.maxConcurrentJobs = v > 0 ? v : 3; }

        public int getMaxGlobalJobs() { return maxGlobalJobs; }
        public void setMaxGlobalJobs(int v) { this.maxGlobalJobs = v > 0 ? v : 8; }

        public int getMaxDepth() { return maxDepth; }
        public void setMaxDepth(int v) { this.maxDepth = v >= 0 ? v : 1; }

        public int getDoomLoopThreshold() { return doomLoopThreshold; }
        public void setDoomLoopThreshold(int v) { this.doomLoopThreshold = v > 0 ? v : 3; }

        public boolean isAutoResume() { return autoResume; }
        public void setAutoResume(boolean autoResume) { this.autoResume = autoResume; }

        public int getEventRetentionDays() { return eventRetentionDays; }
        public void setEventRetentionDays(int v) { this.eventRetentionDays = Math.max(0, v); }
    }

    /**
     * 语义包工具开关。前缀 {@code agent.semantic-pack.*}。
     *
     * <p>{@code enabled} 是 kill-switch（默认开）：关闭后 {@code semanticPack} 工具不进 tools 列表，
     * 语义包只能从「知识」页手工维护，主流程不崩。
     */
    public static class SemanticPackFlags {
        private boolean enabled = true;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }

    /**
     * LLM 自省层开关（P0-2）。两项都会多花一次 LLM 往返（约 2-5s），所以各自留了退路。
     * 放 feature flag 而不是用户可见配置：这两个旋钮是工程取舍，不该进设置面板
     * 再拖两份 i18n 文案副本。
     */
    public static class ReflectionFlags {
        /**
         * reminder 文本是否先过一次 LLM 反思（产出 root_cause / missed_evidence / next_action）。
         *
         * <p>默认开：reminder 本身已经意味着要多跑一轮 LLM，而那一轮原本只能拿到
         * {@code "[reminder] " + reason} 这么一句固定文本。花 2-3s 换一份有信息的诊断，
         * 比让它拿着同一句模板再试一轮更省。
         */
        private boolean rejectionWriterEnabled = true;

        /**
         * 普通会话触发 Expert Critic 所需的最少失败信号数（0 = 保持旧行为，仅 multi_agent 走 critic）。
         *
         * <p>信号口径与 P2-1 的预算升级完全一致（{@code reminderCount + noProgressStreak}），
         * 两处共用同一个定义，不各自重新发明“什么叫走了弯路”。
         *
         * <p>默认 2 而不是 1：critic 在 finish 时触发，延迟加在用户等最终答案的时候，
         * 感知最强；要求“连续返工”而不是“返工过一次”才复审。
         */
        private int criticFailureSignalThreshold = 2;

        public boolean isRejectionWriterEnabled() { return rejectionWriterEnabled; }
        public void setRejectionWriterEnabled(boolean rejectionWriterEnabled) {
            this.rejectionWriterEnabled = rejectionWriterEnabled;
        }

        public int getCriticFailureSignalThreshold() { return criticFailureSignalThreshold; }
        public void setCriticFailureSignalThreshold(int criticFailureSignalThreshold) {
            this.criticFailureSignalThreshold = criticFailureSignalThreshold;
        }
    }

    public static class EvalFlags {
        private double accuracyThreshold = -3.0;
        private double latencyThreshold = 10.0;
        private double tokenThreshold = 10.0;
        private double sseThreshold = 50.0;

        public double getAccuracyThreshold() { return accuracyThreshold; }
        public void setAccuracyThreshold(double accuracyThreshold) { this.accuracyThreshold = accuracyThreshold; }

        public double getLatencyThreshold() { return latencyThreshold; }
        public void setLatencyThreshold(double latencyThreshold) { this.latencyThreshold = latencyThreshold; }

        public double getTokenThreshold() { return tokenThreshold; }
        public void setTokenThreshold(double tokenThreshold) { this.tokenThreshold = tokenThreshold; }

        public double getSseThreshold() { return sseThreshold; }
        public void setSseThreshold(double sseThreshold) { this.sseThreshold = sseThreshold; }
    }

    public static class DebugFlags {
        private SseRecordingFlags sseRecording = new SseRecordingFlags();

        public SseRecordingFlags getSseRecording() { return sseRecording; }
        public void setSseRecording(SseRecordingFlags sseRecording) {
            this.sseRecording = sseRecording != null ? sseRecording : new SseRecordingFlags();
        }
    }

    public static class SseRecordingFlags {
        private boolean enabled = false;
        private String outputDir;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public String getOutputDir() { return outputDir; }
        public void setOutputDir(String outputDir) { this.outputDir = outputDir; }
    }

    /**
     * Phase 2A 正确性守卫开关。默认全部开启（正确性守卫，非实验 feature），
     * 仅作为灰度回退阀门：agent.guardrail.join-fanout-guard-enabled / partition-enforcement-enabled。
     */
    public static class GuardrailFlags {
        public static final int DEFAULT_PARTITION_DAYS = 30;

        private boolean joinFanoutGuardEnabled = true;
        private boolean partitionEnforcementEnabled = true;
        private int partitionDefaultDays = DEFAULT_PARTITION_DAYS;

        public boolean isJoinFanoutGuardEnabled() { return joinFanoutGuardEnabled; }
        public void setJoinFanoutGuardEnabled(boolean joinFanoutGuardEnabled) { this.joinFanoutGuardEnabled = joinFanoutGuardEnabled; }

        public boolean isPartitionEnforcementEnabled() { return partitionEnforcementEnabled; }
        public void setPartitionEnforcementEnabled(boolean partitionEnforcementEnabled) { this.partitionEnforcementEnabled = partitionEnforcementEnabled; }

        public int getPartitionDefaultDays() { return partitionDefaultDays; }
        public void setPartitionDefaultDays(int partitionDefaultDays) {
            this.partitionDefaultDays = partitionDefaultDays > 0 ? partitionDefaultDays : DEFAULT_PARTITION_DAYS;
        }
    }
}
