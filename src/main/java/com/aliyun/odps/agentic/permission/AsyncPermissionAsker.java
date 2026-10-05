package com.aliyun.odps.agentic.permission;

import java.util.concurrent.CompletableFuture;

/**
 * 异步权限询问接口 -- 用于向用户确认权限的抽象接口。
 * 用户根据不同环境（Web、CLI 等）提供实现。
 */
@FunctionalInterface
public interface AsyncPermissionAsker {

    /**
     * 异步询问用户是否授权。
     *
     * @param request 权限请求
     * @return 异步权限回复
     */
    CompletableFuture<PermissionReply> askAsync(PermissionRequest request);
}
