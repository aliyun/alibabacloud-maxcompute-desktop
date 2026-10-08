package com.aliyun.odps.agentic.memory.knowledge;

import java.util.List;
import java.util.Map;

/**
 * 检索模型客户端——百炼原生 embedding/rerank API。
 *
 * <p>实测钉死的协议形状（POC 2026-10-06）：embedding 请求 {@code input.texts[]}（非裸数组），
 * 响应索引进 {@code index}（非 text_index）；入库 {@code text_type=document}，
 * 检索 {@code text_type=query}（可带 instruct，官方称 +1~5%）。</p>
 *
 * <p>批量上限按模型分派：qwen3.7 系 20 行/批，text-embedding-v4 10 行/批。</p>
 */
public class DashScopeRetrievalClient {

    /** 检索契约（设计文档 §3 等价键）：同一组值的向量才可同空间比较。 */
    public record RetrievalContract(String embeddingModel, int dimension, String distanceMetric,
                                    String documentTextType, String queryTextType) {
        public static RetrievalContract of(String model, int dimension) {
            return new RetrievalContract(model, dimension, "cosine", "document", "query");
        }
    }

    private com.aliyun.odps.agentic.operation.ModelOperationRunner operations=new com.aliyun.odps.agentic.operation.ModelOperationRunner();
    public void setModelOperations(com.aliyun.odps.agentic.operation.ModelOperationRunner runner) { this.operations=java.util.Objects.requireNonNull(runner); }
    private final String apiKey;
    private final String baseUrl;

    public DashScopeRetrievalClient(String apiKey) {
        this(apiKey, "https://dashscope.aliyuncs.com");
    }

    public DashScopeRetrievalClient(String apiKey, String baseUrl) {
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
    }

    /** 模型单次批量上限（v4=10，其余 20）。 */
    public static int batchLimitOf(String model) {
        return "text-embedding-v4".equals(model) ? 10 : 20;
    }

    /**
     * 批量 embedding。
     *
     * @param textType document（入库）/ query（检索）
     * @param instruct 仅 query 生效，英文撰写；可为 null
     * @return 与 texts 等长、按输入顺序对齐的向量数组
     */
    @SuppressWarnings("unchecked")
    public float[][] embed(String model, int dimension, List<String> texts, String textType, String instruct) {
        return operations.run("retrieval.embedding",com.aliyun.odps.agentic.operation.ModelOperationRunner.Modality.TEXT,
            progress -> embedInternal(model,dimension,texts,textType,instruct));
    }
    private float[][] embedInternal(String model,int dimension,List<String> texts,String textType,String instruct) {
        if (texts.isEmpty()) return new float[0][];
        Map<String, Object> params = new java.util.LinkedHashMap<>();
        params.put("dimension", dimension);
        params.put("output_type", "dense");
        params.put("text_type", textType);
        if (instruct != null && !instruct.isBlank() && "query".equals(textType)) {
            params.put("instruct", instruct);
        }
        Map<String, Object> body = Map.of(
                "model", model,
                "input", Map.of("texts", texts),
                "parameters", params);
        Map<String, Object> resp = post("/api/v1/services/embeddings/text-embedding/text-embedding", body);
        Map<String, Object> output = (Map<String, Object>) resp.get("output");
        List<Map<String, Object>> embeddings = output != null ? (List<Map<String, Object>>) output.get("embeddings") : null;
        if (embeddings == null || embeddings.size() != texts.size()) {
            throw new IllegalStateException("embedding 响应条数不符: expect=" + texts.size()
                    + " got=" + (embeddings == null ? 0 : embeddings.size()));
        }
        float[][] out = new float[texts.size()][];
        for (Map<String, Object> e : embeddings) {
            int idx = ((Number) e.get("index")).intValue();
            List<Number> vec = (List<Number>) e.get("embedding");
            float[] f = new float[vec.size()];
            for (int i = 0; i < vec.size(); i++) f[i] = vec.get(i).floatValue();
            out[idx] = f;
        }
        return out;
    }

    /**
     * 重排序。qwen3.7-text-rerank：input {query, documents[]}，output.results[*] = {index, relevance_score}。
     *
     * @return 与 documents 等长的分数数组（按输入序）
     */
    @SuppressWarnings("unchecked")
    public double[] rerank(String model, String query, List<String> documents) {
        return operations.run("retrieval.rerank",com.aliyun.odps.agentic.operation.ModelOperationRunner.Modality.TEXT,
            progress -> rerankInternal(model,query,documents));
    }
    private double[] rerankInternal(String model,String query,List<String> documents) {
        if (documents.isEmpty()) return new double[0];
        Map<String, Object> body = Map.of(
                "model", model,
                "input", Map.of("query", query, "documents", documents),
                "parameters", Map.of("return_documents", false));
        Map<String, Object> resp = post("/api/v1/services/rerank/text-rerank/text-rerank", body);
        Map<String, Object> output = (Map<String, Object>) resp.get("output");
        List<Map<String, Object>> results = output != null ? (List<Map<String, Object>>) output.get("results") : null;
        if (results == null) throw new IllegalStateException("rerank 响应缺 results");
        double[] scores = new double[documents.size()];
        for (Map<String, Object> r : results) {
            int idx = ((Number) r.get("index")).intValue();
            Object score = r.get("relevance_score");
            if (idx >= 0 && idx < scores.length && score instanceof Number) {
                scores[idx] = ((Number) score).doubleValue();
            }
        }
        return scores;
    }

    /** 配额耗尽(不可重试,明天/提额才恢复) */
    public static class QuotaExhaustedException extends IllegalStateException {
        public QuotaExhaustedException(String msg) { super(msg); }
    }

    private Map<String, Object> post(String path, Map<String, Object> body) {
        int backoffMs = 1000;
        for (int attempt = 0; ; attempt++) {
            try {
                var client = java.net.http.HttpClient.newBuilder()
                        .connectTimeout(java.time.Duration.ofSeconds(15)).build();
                var req = java.net.http.HttpRequest.newBuilder()
                        .uri(java.net.URI.create(baseUrl + path))
                        .timeout(java.time.Duration.ofSeconds(120))
                        .header("Authorization", "Bearer " + apiKey)
                        .header("Content-Type", "application/json")
                        .POST(java.net.http.HttpRequest.BodyPublishers.ofString(
                                new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(body)))
                        .build();
                var resp = client.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() == 200) {
                    return new com.fasterxml.jackson.databind.ObjectMapper().readValue(resp.body(), Map.class);
                }
                String errBody = resp.body() == null ? "" : resp.body();
                // 配额耗尽:不重试(实证:AllocationQuota 是账户级,退避无用)
                if (errBody.contains("AllocationQuota")) {
                    throw new QuotaExhaustedException("百炼配额已用尽(提额或次日恢复): " + errBody.substring(0, Math.min(200, errBody.length())));
                }
                // 瞬时限流:指数退避,尊重 Retry-After
                if (resp.statusCode() == 429 && attempt < 3) {
                    long wait = resp.headers().firstValueAsLong("Retry-After").orElse(0) * 1000;
                    if (wait <= 0) wait = backoffMs;
                    Thread.sleep(wait);
                    backoffMs *= 2;
                    continue;
                }
                throw new IllegalStateException("检索模型 HTTP " + resp.statusCode() + ": " + errBody.substring(0, Math.min(300, errBody.length())));
            } catch (QuotaExhaustedException e) {
                throw e;
            } catch (IllegalStateException e) {
                throw e;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("检索模型调用被中断", e);
            } catch (Exception e) {
                throw new IllegalStateException("检索模型调用失败: " + e.getMessage(), e);
            }
        }
    }
}
