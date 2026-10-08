package com.aliyun.odps.agentic.config;

import com.aliyun.odps.agentic.operation.ModelOperationRunner;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.aliyun.odps.agentic.config.ModelVisionCapability;
import com.aliyun.odps.agentic.config.AIConfigSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * 运行时图片输入能力探针（多模态动态适配，2026-08-21）。
 *
 * <p>方法：同一条极小请求发两次 —— 一次纯文本，一次带一张 10×10 PNG，两次都 {@code max_tokens=1}。
 * 只看 {@code usage.prompt_tokens} 的差值：
 * <pre>
 *   Δ &gt; 0  → VISION      图片被真实消费（实测 10×10 图的 token 地板是 77）
 *   Δ == 0 → TEXT_ONLY   静默丢弃（最危险形态：HTTP 200 却对图无感）
 *   400 + InvalidParameter → TEXT_ONLY   显式拒绝
 *   其他非 2xx / 超时      → UNKNOWN     环境噪声，不作结论、不落库
 * </pre>
 *
 * <p>为什么不按模型名正则判断：{@code qwen3.8-max} 支持而 {@code qwen3.7-max} 不支持，
 * 同族同代仅小版本之差就翻转（实测 §1 P6b）—— 任何 contains/前缀规则都会判错。
 *
 * <p>为什么图片要 ≥ 10 px：实测 1/4/8 px 一律 HTTP 400（"image is too small"），
 * 10 px 是最小可接受边长（§1 P6a）。
 *
 * <p>成本：两次 {@code max_tokens=1} 调用，合计 ~140 input token，一个模型一辈子只跑一次。
 */
public class VisionCapabilityProbe {

    private static final Logger log = LoggerFactory.getLogger(VisionCapabilityProbe.class);

    /** 10×10 纯白 PNG（73 字节）。最小可接受边长，见 §1 P6a。包级可见：单测校验 ≥10px 红线。 */
    public static final String PROBE_PNG_B64 =
        "iVBORw0KGgoAAAANSUhEUgAAAAoAAAAKCAIAAAACUFjqAAAAEElEQVR42mP4jxcwjEpjAwD6Hirkf4B3HgAAAABJRU5ErkJggg==";

    private static final String PROBE_TEXT = "hi";

    /** 单次探测超时。探针请求极小，正常 1~3s；给到 20s 覆盖冷启动抖动。 */
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(20);

    private final ObjectMapper objectMapper = new ObjectMapper();
    private ModelOperationRunner modelOperations = new ModelOperationRunner();

    public void setModelOperations(ModelOperationRunner modelOperations) {
        this.modelOperations = modelOperations;
    }
    private final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(8))
        .build();

    /**
     * 探测给定配置的图片输入能力。永不抛异常 —— 任何意外都返回 {@link ModelVisionCapability#UNKNOWN}。
     */
    public ModelVisionCapability probe(AIConfigSnapshot snapshot) {
        if (snapshot == null || !snapshot.isValid()
            || snapshot.normalizedApiUrl() == null || snapshot.apiKey() == null) {
            return ModelVisionCapability.UNKNOWN;
        }

        long started = System.currentTimeMillis();
        Result textOnly = modelOperations.runClassified("vision.probe.text", ModelOperationRunner.Modality.VISION,
            progress -> call(snapshot, false),
            result -> result.status >= 200 && result.status < 300 && result.promptTokens >= 0,
            event -> log.debug("[VisionProbe][SDK] operation={} phase={} elapsedMs={}",
                event.name(), event.phase(), event.elapsedMs()));
        if (textOnly.status < 200 || textOnly.status >= 300 || textOnly.promptTokens < 0) {
            log.info("[VisionProbe] {} baseline call failed (status={}), verdict=UNKNOWN",
                snapshot.modelName(), textOnly.status);
            return ModelVisionCapability.UNKNOWN;
        }

        Result withImage = modelOperations.runClassified("vision.probe.image", ModelOperationRunner.Modality.VISION,
            progress -> call(snapshot, true),
            result -> result.status >= 200 && result.status < 300,
            event -> log.debug("[VisionProbe][SDK] operation={} phase={} elapsedMs={}",
                event.name(), event.phase(), event.elapsedMs()));

        ModelVisionCapability verdict = decide(textOnly, withImage, snapshot.modelName());
        log.info("[VisionProbe] {} verdict={} (txt={} img={} status={} elapsed={}ms)",
            snapshot.modelName(), verdict, textOnly.promptTokens, withImage.promptTokens,
            withImage.status, System.currentTimeMillis() - started);
        return verdict;
    }

    /** 包级可见：裁定规则是三分支纯函数，单测直接喂 Result 覆盖，不必打网络。 */
    ModelVisionCapability decide(Result textOnly, Result withImage, String model) {
        // 1) 显式拒绝：400 且带参数类错误码 → 确定不支持
        if (withImage.status == 400) {
            String body = withImage.body == null ? "" : withImage.body;
            if (body.contains("InvalidParameter") || body.contains("invalid_parameter")
                || body.contains("invalid_request_error")) {
                return ModelVisionCapability.TEXT_ONLY;
            }
            log.info("[VisionProbe] {} got 400 without InvalidParameter marker, verdict=UNKNOWN: {}",
                model, truncate(body));
            return ModelVisionCapability.UNKNOWN;
        }
        // 2) 非 2xx（401/403/429/5xx/超时=-1）→ 环境噪声，不作结论
        if (withImage.status < 200 || withImage.status >= 300 || withImage.promptTokens < 0) {
            return ModelVisionCapability.UNKNOWN;
        }
        // 3) 2xx：只看 prompt_tokens 差值
        return withImage.promptTokens > textOnly.promptTokens
            ? ModelVisionCapability.VISION
            : ModelVisionCapability.TEXT_ONLY;
    }

    private Result call(AIConfigSnapshot snapshot, boolean withImage) {
        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("model", snapshot.modelName());
            body.put("max_tokens", 1);
            body.put("stream", false);
            ArrayNode messages = body.putArray("messages");
            ObjectNode user = messages.addObject();
            user.put("role", "user");
            ArrayNode content = user.putArray("content");
            if (withImage) {
                ObjectNode img = content.addObject();
                img.put("type", "image_url");
                img.putObject("image_url").put("url", "data:image/png;base64," + PROBE_PNG_B64);
            }
            content.addObject().put("type", "text").put("text", PROBE_TEXT);

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(snapshot.normalizedApiUrl()))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + snapshot.apiKey())
                .timeout(PROBE_TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body),
                    StandardCharsets.UTF_8))
                .build();

            HttpResponse<String> resp = httpClient.send(request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int promptTokens = -1;
            if (resp.statusCode() >= 200 && resp.statusCode() < 300 && resp.body() != null) {
                JsonNode root = objectMapper.readTree(resp.body());
                JsonNode pt = root.path("usage").path("prompt_tokens");
                promptTokens = pt.isNumber() ? pt.asInt() : -1;
            }
            return new Result(resp.statusCode(), promptTokens, resp.body());
        } catch (Exception e) {
            log.debug("[VisionProbe] call failed (withImage={}): {}", withImage, e.toString());
            return new Result(-1, -1, e.toString());
        }
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() <= 200 ? s : s.substring(0, 200) + "...";
    }

    /** 单次探测结果。{@code promptTokens < 0} 表示没读到 usage。 */
    record Result(int status, int promptTokens, String body) {}
}
