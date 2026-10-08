package com.aliyun.odps.agentic.memory.context;


import java.util.concurrent.atomic.AtomicLong;

/**
 * EpisodicMemory FTS5/BM25 指标埋点。
 *
 * <p>线程安全：所有计数器走 {@link AtomicLong}；top1 score 用 6 位小数定点（×1_000_000）累加，
 * 取平均时再除回 double，避免 double 累加的精度漂移。
 */
public class EpisodicMemoryMetrics {
    private final AtomicLong queryCount = new AtomicLong(0);
    private final AtomicLong hitCount = new AtomicLong(0);
    private final AtomicLong crossSessionHit = new AtomicLong(0);
    private final AtomicLong top1ScoreSum = new AtomicLong(0);  // 累加，平均时除以 hitCount

    public void recordQuery() { queryCount.incrementAndGet(); }

    public void recordHit(double top1Score, boolean crossSession) {
        hitCount.incrementAndGet();
        top1ScoreSum.addAndGet((long) (top1Score * 1_000_000));  // 6 位小数定点
        if (crossSession) crossSessionHit.incrementAndGet();
    }

    public long getQueryCount() { return queryCount.get(); }
    public long getHitCount() { return hitCount.get(); }
    public long getCrossSessionHit() { return crossSessionHit.get(); }

    public double getAverageTop1Score() {
        long h = hitCount.get();
        return h == 0 ? 0.0 : (top1ScoreSum.get() / 1_000_000.0) / h;
    }
}
