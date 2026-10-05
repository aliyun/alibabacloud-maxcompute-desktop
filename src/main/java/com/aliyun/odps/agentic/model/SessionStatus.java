package com.aliyun.odps.agentic.model;

/**
 * 会话状态枚举，表示会话当前的运行状态。
 */
public enum SessionStatus {
    IDLE,
    BUSY,
    SUSPENDED,
    ERROR
}
