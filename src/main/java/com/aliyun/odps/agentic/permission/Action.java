package com.aliyun.odps.agentic.permission;

/**
 * 权限动作 -- 规则匹配后执行的操作。
 */
public enum Action {
    /** 直接放行，无需询问。 */
    ALLOW,
    /** 拒绝该操作。 */
    DENY,
    /** 向用户请求确认。 */
    ASK
}
