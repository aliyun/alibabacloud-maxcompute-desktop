package com.aliyun.odps.agentic.session;

/**
 * 表示运行循环在当前阶段后的下一步动作。
 *
 * <p>可用于决定继续执行、先做上下文压缩，或直接停止。
 */
public enum ProcessResult {
    /** 继续进入下一轮。 */
    CONTINUE,
    /** 需要先进行上下文压缩。 */
    COMPACT,
    /** 停止运行循环。 */
    STOP
}
