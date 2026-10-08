package com.aliyun.odps.agentic.config;
import com.aliyun.odps.agentic.config.ModelVisionCapability;

import com.aliyun.odps.agentic.config.AIConfigSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 图片输入能力解析（三级：强制开关 → 内存/持久化/种子 → 运行时探针）。
 *
 * <p>查询顺序，先命中先返回：
 * <ol>
 *   <li><b>强制开关</b> {@code agent.multimodal.force-capability=auto|vision|text} —— 运维逃生门，
 *       新模型上线当天不必等我们改代码</li>
 *   <li><b>进程内 memo</b> —— 热路径零开销</li>
 *   <li><b>持久化</b>（{@code user_preferences} 表，key 含 {@code endpointHost + modelName + 探针版本}）
 *       —— 一个模型探一次，重启后仍然有效</li>
 *   <li><b>实测种子表</b> —— 已实测过的模型直接给结论，连一次探针都省</li>
 *   <li><b>运行时探针</b> —— 全都没命中时才发两次 {@code max_tokens=1} 请求（single-flight，
 *       同一 key 并发只探一次）</li>
 * </ol>
 *
 * <p>为什么种子表用<b>精确全名</b>而不是 {@code ModelCapabilityRegistry} 那种 contains 匹配：
 * 实测 {@code qwen3.8-max} 支持图片而 {@code qwen3.7-max} 显式 400 —— 同族同代仅小版本之差就翻转，
 * 前缀/包含规则必然判错（这也是那份 registry 的 {@code REASONING_CAPABLE_PATTERNS} 不能复用到模态判断上的原因）。
 *
 * <p>fail-safe 方向：拿不到结论一律当 {@link ModelVisionCapability#TEXT_ONLY} 用（{@code canSendImages()==false}），
 * 因为"静默丢弃"形态的模型会 HTTP 200 地假装看过图，代价比降级成纯文本大得多。
 */
public class ModelVisionCapabilityResolver implements AutoCloseable {
    public interface Preferences { String get(String key); void set(String key,String value); }

    private static final Logger log = LoggerFactory.getLogger(ModelVisionCapabilityResolver.class);

    /** 探针版本。判据（探针图尺寸/裁定规则）变化时 +1，让历史结论自然失效重探。 */
    private static final String PROBE_VERSION = "v1";
    private static final String PREF_KEY_PREFIX = "agent.vision.cap." + PROBE_VERSION + ".";

    /**
     * 实测确认消费图片（2026-08-21，DashScope compatible-mode，§1 P6b：Δprompt_tokens &gt; 0）。
     *
     * <p>只收<b>干净读数</b>。{@code qwen3.6-plus}（探测超时 http=-1）不入表
     * —— 那是环境噪声不是模态结论，让运行时探针自己去测。
     * {@code kimi-k3} 更正（2026-10-04）：早期探测曾 403（当时账号未开通，环境噪声）；
     * 权限开通后探针实测 VISION 并持久化（user_preferences `agent.vision.cap.v1...|kimi-k3`），
     * 已入种子表。
     */
    private static final Set<String> SEED_VISION = Set.of(
        "qwen3.8-max", "qwen3.8-27b", "qwen3.7-plus", "qwen3.5-plus", "kimi-k3");

    /** 实测确认不消费图片：显式 400（qwen3.7-max）或静默丢弃（其余，§1 P6b）。 */
    private static final Set<String> SEED_TEXT_ONLY = Set.of(
        "qwen3.7-max", "qwen3-max", "glm-5.2", "glm-5", "deepseek-v4-pro");

    private final VisionCapabilityProbe probe;
    private final Preferences preferenceRepository;

    /** auto | vision | text —— text/vision 直接短路，不查表不探针。 */
    private final String forceCapability;

    private final Map<String, ModelVisionCapability> memo = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<ModelVisionCapability>> inflight = new ConcurrentHashMap<>();
    private final ExecutorService probeExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "vision-capability-probe");
        t.setDaemon(true);
        return t;
    });

    public ModelVisionCapabilityResolver(VisionCapabilityProbe probe,
                                        Preferences preferenceRepository,
                                        String forceCapability) {
        this.probe = probe;
        this.preferenceRepository = preferenceRepository;
        this.forceCapability = forceCapability == null ? "auto" : forceCapability.trim().toLowerCase();
    }

    /**
     * 不阻塞地取结论：命中缓存/种子/持久化就返回，否则返回 UNKNOWN 并在后台起一次探针。
     * 用于"要不要在提示词里声明可看图"这类可以下一轮再对的判断。
     */
    public ModelVisionCapability peek(AIConfigSnapshot snapshot) {
        return resolve(snapshot, 0L);
    }

    /**
     * 取结论，必要时等探针最多 {@code waitMs}。用于真的挂着图要发的那一刻。
     *
     * <p>等不到就返回 UNKNOWN（本轮降级纯文本），探针仍在后台跑完并落库 —— 下一轮就是确定结论。
     */
    public ModelVisionCapability resolve(AIConfigSnapshot snapshot, long waitMs) {
        if ("text".equals(forceCapability) || "text_only".equals(forceCapability)) {
            return ModelVisionCapability.TEXT_ONLY;
        }
        if ("vision".equals(forceCapability)) {
            return ModelVisionCapability.VISION;
        }
        if (snapshot == null || snapshot.modelName() == null || snapshot.modelName().isBlank()) {
            return ModelVisionCapability.UNKNOWN;
        }

        String key = cacheKey(snapshot);
        ModelVisionCapability hit = memo.get(key);
        if (hit != null) return hit;

        ModelVisionCapability persisted = readPersisted(key);
        if (persisted != null) {
            memo.put(key, persisted);
            return persisted;
        }

        ModelVisionCapability seed = seedOf(snapshot.modelName());
        if (seed != null) {
            memo.put(key, seed);
            return seed;
        }

        CompletableFuture<ModelVisionCapability> future = startProbe(key, snapshot);
        if (waitMs <= 0) return ModelVisionCapability.UNKNOWN;
        try {
            ModelVisionCapability result = future.get(waitMs, TimeUnit.MILLISECONDS);
            return result == null ? ModelVisionCapability.UNKNOWN : result;
        } catch (Exception e) {
            log.info("[VisionCap] probe not ready within {}ms for {} — degrade this turn", waitMs, key);
            return ModelVisionCapability.UNKNOWN;
        }
    }

    /** single-flight：同一 key 并发只跑一次探针。 */
    private CompletableFuture<ModelVisionCapability> startProbe(String key, AIConfigSnapshot snapshot) {
        return inflight.computeIfAbsent(key, k -> CompletableFuture.supplyAsync(() -> {
            ModelVisionCapability verdict = probe.probe(snapshot);
            if (verdict != ModelVisionCapability.UNKNOWN) {
                // 只落确定结论。UNKNOWN 来自环境噪声（401/429/超时），落库会把噪声变成永久错判。
                memo.put(k, verdict);
                writePersisted(k, verdict);
            }
            return verdict;
        }, probeExecutor).whenComplete((v, e) -> inflight.remove(k)));
    }

    private static ModelVisionCapability seedOf(String modelName) {
        String m = modelName.trim().toLowerCase();
        if (SEED_VISION.contains(m)) return ModelVisionCapability.VISION;
        if (SEED_TEXT_ONLY.contains(m)) return ModelVisionCapability.TEXT_ONLY;
        return null;
    }

    private ModelVisionCapability readPersisted(String key) {
        if (preferenceRepository == null) return null;
        try {
            String raw = preferenceRepository.get(PREF_KEY_PREFIX + key);
            if (raw == null || raw.isBlank()) return null;
            ModelVisionCapability cap = ModelVisionCapability.valueOf(raw.trim());
            return cap == ModelVisionCapability.UNKNOWN ? null : cap;
        } catch (Exception e) {
            return null;
        }
    }

    private void writePersisted(String key, ModelVisionCapability cap) {
        if (preferenceRepository == null) return;
        try {
            preferenceRepository.set(PREF_KEY_PREFIX + key, cap.name());
        } catch (Exception e) {
            log.debug("[VisionCap] persist failed for {}: {}", key, e.getMessage());
        }
    }

    /** key = endpoint host + model name。同名模型在不同 endpoint 上能力可能不同。 */
    private static String cacheKey(AIConfigSnapshot snapshot) {
        String host = "unknown";
        try {
            if (snapshot.normalizedApiUrl() != null) {
                String h = URI.create(snapshot.normalizedApiUrl()).getHost();
                if (h != null && !h.isBlank()) host = h;
            }
        } catch (Exception ignored) {
            // 非法 URL：退化成 unknown host，不影响功能（只是不同 endpoint 会共用一条结论）
        }
        return host + "|" + snapshot.modelName().trim().toLowerCase();
    }

    /** 测试/诊断用：清空进程内缓存（不动持久化）。 */
    public void clearMemo() {
        memo.clear();
    }
    @Override public void close() { probeExecutor.shutdownNow(); }
}
