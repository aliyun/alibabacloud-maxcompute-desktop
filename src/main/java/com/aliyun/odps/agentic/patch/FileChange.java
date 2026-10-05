package com.aliyun.odps.agentic.patch;

import java.nio.file.Path;

/**
 * 单个文件变更记录。
 *
 * @param path       文件路径
 * @param oldContent 变更前内容
 * @param newContent 变更后内容
 * @param type       变更类型
 * @param diff       unified diff 字符串，可为空
 */
public record FileChange(
    Path path,
    String oldContent,
    String newContent,
    ChangeType type,
    String diff
) {}
