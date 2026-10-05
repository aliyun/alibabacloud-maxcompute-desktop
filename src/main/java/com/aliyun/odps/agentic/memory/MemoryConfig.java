package com.aliyun.odps.agentic.memory;

/**
 * 记忆配置。
 * 用于定义长期记忆文件、每日日志目录和压缩阈值。
 *
 * @param longTermFile     长期记忆文件路径，通常为 {@code MEMORY.md}
 * @param dailyNotesDir    每日记录文件目录
 * @param maxDailyNoteSize 每日日志在压缩前允许的最大字节数
 */
public record MemoryConfig(
    String longTermFile,
    String dailyNotesDir,
    int maxDailyNoteSize
) {
    /**
     * 创建默认记忆配置。
     *
     * @return 默认配置实例
     */
    public static MemoryConfig defaultConfig() {
        return new MemoryConfig("MEMORY.md", "memory", 100_000);
    }
}
