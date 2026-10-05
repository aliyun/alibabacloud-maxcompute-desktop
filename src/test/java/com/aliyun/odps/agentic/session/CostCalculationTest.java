package com.aliyun.odps.agentic.session;

import com.aliyun.odps.agentic.llm.Model;
import com.aliyun.odps.agentic.llm.ModelLimit;
import com.aliyun.odps.agentic.llm.Usage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 成本计算按模型真实单价计费的回归测试（0.4.0）。
 *
 * <p>此前 {@code calculateCost} 无论跑什么模型都硬编码按 Sonnet（3.0/15.0）计价，
 * 跑 qwen/DashScope 时账面数字是错的。本测试锁定「按目录单价 + 兜底默认费率」两条路径。
 */
class CostCalculationTest {

    private static Usage usage(int in, int out, int cacheRead, int cacheWrite) {
        return new Usage(in, out, cacheRead, cacheWrite, 0);
    }

    @Test
    void usesCatalogPricingForKnownModel() {
        // claude-sonnet-4-20250514：input=3.0, output=15.0, cacheRead=0.3, cacheWrite=3.75 / 1M
        Model sonnet = Model.of("anthropic", "claude-sonnet-4-20250514", new ModelLimit(200000, null, 64000));
        double cost = StreamProcessor.calculateCost(usage(1_000_000, 0, 0, 0), sonnet);
        assertEquals(3.0, cost, 1e-6, "1M input tokens at Sonnet rate should be $3.0");
    }

    @Test
    void differentModelsProduceDifferentCost() {
        // 同样的用量，Sonnet(3.0) 与 Haiku(1.0) 的输入单价不同 → 成本必须不同。
        Usage u = usage(1_000_000, 0, 0, 0);
        Model sonnet = Model.of("anthropic", "claude-sonnet-4-20250514", new ModelLimit(200000, null, 64000));
        Model haiku = Model.of("anthropic", "claude-haiku-4-5-20251001", new ModelLimit(200000, null, 64000));

        double sonnetCost = StreamProcessor.calculateCost(u, sonnet);
        double haikuCost = StreamProcessor.calculateCost(u, haiku);

        assertNotEquals(sonnetCost, haikuCost, "different models must not share one hardcoded rate");
        assertEquals(3.0, sonnetCost, 1e-6);
        assertEquals(1.0, haikuCost, 1e-6, "Haiku input is $1.0/1M");
    }

    @Test
    void unknownModelFallsBackToDefaultRate() {
        // 目录里没有的模型（例如未收录的 qwen/DashScope 变体）→ 回退默认 Sonnet 档费率，不抛错。
        Model unknown = Model.of("dashscope", "qwen-unlisted-xyz", new ModelLimit(128000, null, 8192));
        double cost = StreamProcessor.calculateCost(usage(1_000_000, 0, 0, 0), unknown);
        assertEquals(3.0, cost, 1e-6, "unknown model must fall back to default rate, not throw or zero");
    }

    @Test
    void nullModelUsesDefaultRate() {
        double cost = StreamProcessor.calculateCost(usage(1_000_000, 1_000_000, 0, 0), null);
        // 3.0*1 + 15.0*1 = 18.0
        assertEquals(18.0, cost, 1e-6);
    }

    @Test
    void cacheTokensArePricedSeparately() {
        // 1M 输入全部来自缓存读取：input 部分应为 0，只收 cacheRead 价。
        Model sonnet = Model.of("anthropic", "claude-sonnet-4-20250514", new ModelLimit(200000, null, 64000));
        double cost = StreamProcessor.calculateCost(usage(1_000_000, 0, 1_000_000, 0), sonnet);
        assertEquals(0.3, cost, 1e-6, "cache-read tokens billed at cacheRead rate only");
    }
}
