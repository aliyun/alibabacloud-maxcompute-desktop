package com.aliyun.odps.agentic.model;

import java.time.Instant;

/**
 * 轻量级会话信息，用于会话列表查询。
 *
 * @param id        会话 ID
 * @param title     会话标题
 * @param status    当前状态
 * @param createdAt 创建时间
 * @param updatedAt 最后更新时间
 */
public record SessionInfo(
    String id,
    String title,
    SessionStatus status,
    Instant createdAt,
    Instant updatedAt
) {}
