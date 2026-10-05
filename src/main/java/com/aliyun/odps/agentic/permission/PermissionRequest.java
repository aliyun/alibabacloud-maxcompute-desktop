package com.aliyun.odps.agentic.permission;

import java.util.UUID;

/**
 * 待确认的权限请求 -- 表示需要用户确认的权限数据对象。
 *
 * @param id          唯一请求 ID
 * @param sessionId   请求所属的会话 ID
 * @param tool        请求权限的工具名称
 * @param callId      工具调用 ID
 * @param permission  权限类别（如 {@code "file"}、{@code "bash"}）
 * @param target      操作目标（如文件路径、命令）
 * @param description 可读的请求描述
 */
public record PermissionRequest(
    String id,
    String sessionId,
    String tool,
    String callId,
    String permission,
    String target,
    String description
) {
    /**
     * 创建一个新的权限请求，自动生成唯一 ID。
     *
     * @param sessionId   会话 ID
     * @param tool        工具名称
     * @param callId      工具调用 ID
     * @param permission  权限类别
     * @param target      操作目标
     * @param description 请求描述
     * @return 新建的权限请求
     */
    public static PermissionRequest create(String sessionId, String tool, String callId,
                                            String permission, String target, String description) {
        return new PermissionRequest(UUID.randomUUID().toString(), sessionId, tool, callId,
                                      permission, target, description);
    }
}
