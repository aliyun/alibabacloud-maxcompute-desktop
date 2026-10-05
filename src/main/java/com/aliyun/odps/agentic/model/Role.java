package com.aliyun.odps.agentic.model;

/**
 * 消息角色枚举，标识消息的发送方。
 * 在运行循环中用于区分用户、助手和系统消息。
 */
public enum Role {
    USER,
    ASSISTANT,
    SYSTEM
}
