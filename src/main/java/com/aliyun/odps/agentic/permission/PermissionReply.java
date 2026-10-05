package com.aliyun.odps.agentic.permission;

/**
 * 权限回复 -- 用户对权限请求的响应。
 * 支持三种回复类型：仅本次、始终允许、拒绝。
 */
public enum PermissionReply {
    /** 仅允许本次调用。 */
    ONCE,
    /** 始终允许，并将规则添加到运行时规则中。 */
    ALWAYS,
    /** 拒绝，并级联拒绝当前会话中所有待处理的请求。 */
    REJECT
}
