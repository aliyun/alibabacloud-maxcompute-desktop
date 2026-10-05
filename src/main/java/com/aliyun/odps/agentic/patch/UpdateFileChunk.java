package com.aliyun.odps.agentic.patch;

import java.util.List;

/**
 * 更新块 -- 表示一次文件更新中的单个替换片段。
 *
 * <p>补丁格式使用 {@code @@} 标记分隔片段，每个片段定义待替换的旧行和插入的新行。
 *
 * @param oldLines      需要匹配并替换的旧行
 * @param newLines      替换后的新行
 * @param changeContext 用于模糊匹配的上下文提示，可为空
 * @param isEndOfFile   该片段是否作用于文件末尾
 */
public record UpdateFileChunk(
    List<String> oldLines,
    List<String> newLines,
    String changeContext,
    boolean isEndOfFile
) {}
