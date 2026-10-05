package com.aliyun.odps.agentic.patch;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.List;

/**
 * 补丁块密封接口 -- 表示补丁中的基本变更单元。
 * 包含新增文件、删除文件和更新文件三种变体。
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = Hunk.AddHunk.class, name = "add"),
    @JsonSubTypes.Type(value = Hunk.DeleteHunk.class, name = "delete"),
    @JsonSubTypes.Type(value = Hunk.UpdateHunk.class, name = "update")
})
public sealed interface Hunk
    permits Hunk.AddHunk, Hunk.DeleteHunk, Hunk.UpdateHunk {

    /**
     * 新增文件补丁块。
     *
     * @param path     要创建的文件路径
     * @param contents 文件内容行
     */
    record AddHunk(String path, List<String> contents) implements Hunk {}

    /**
     * 删除文件补丁块。
     *
     * @param path 要删除的文件路径
     */
    record DeleteHunk(String path) implements Hunk {}

    /**
     * 更新文件补丁块。
     *
     * @param path     要更新的文件路径
     * @param movePath 若涉及重命名则为新路径，可为空
     * @param chunks   有序的更新片段列表
     */
    record UpdateHunk(
        String path,
        String movePath,
        List<UpdateFileChunk> chunks
    ) implements Hunk {}
}
