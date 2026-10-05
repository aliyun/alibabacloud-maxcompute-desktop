package com.aliyun.odps.agentic.session;

/**
 * 表示触发上下文压缩的原因。
 *
 * <p>{@link #AUTO} 表示系统自动触发，{@link #MANUAL} 表示显式请求触发。
 */
public enum CompactionReason {
    AUTO,
    MANUAL
}
