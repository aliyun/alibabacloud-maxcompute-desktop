package com.aliyun.odps.agentic.permission;

import java.util.concurrent.CompletableFuture;

/**
 * 默认权限询问器 -- 自动放行所有请求。
 * 适用于非交互场景，用户可在交互场景下覆盖此行为。
 */
public class DefaultPermissionAsker implements AsyncPermissionAsker {
    @Override
    public CompletableFuture<PermissionReply> askAsync(PermissionRequest request) {
        return CompletableFuture.completedFuture(PermissionReply.ONCE);
    }
}
