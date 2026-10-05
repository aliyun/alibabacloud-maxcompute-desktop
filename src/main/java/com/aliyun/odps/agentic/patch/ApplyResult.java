package com.aliyun.odps.agentic.patch;

import java.nio.file.Path;
import java.util.List;

/**
 * 补丁应用结果。
 * 用于表示补丁应用后的文件变更列表以及整体是否成功。
 *
 * @param changes 已应用的文件变更列表
 * @param success 补丁是否应用成功
 */
public record ApplyResult(
    List<FileChange> changes,
    boolean success
) {
    /**
     * 创建失败结果。
     *
     * @param reason 失败原因
     * @return 失败的应用结果
     */
    public static ApplyResult failure(String reason) {
        return new ApplyResult(List.of(), false);
    }

    /**
     * 创建成功结果。
     *
     * @param changes 文件变更列表
     * @return 成功的应用结果
     */
    public static ApplyResult success(List<FileChange> changes) {
        return new ApplyResult(changes, true);
    }
}
