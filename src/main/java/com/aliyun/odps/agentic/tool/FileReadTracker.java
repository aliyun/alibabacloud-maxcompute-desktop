package com.aliyun.odps.agentic.tool;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 跟踪会话运行期间已读取的文件。
 * Write/Edit 工具通过此跟踪器拒绝修改未先读取的文件，实现"先读后写"保护。
 */
public class FileReadTracker {

    public static final String CONTEXT_KEY = "__file_read_tracker__";

    private final Set<String> readFiles = ConcurrentHashMap.newKeySet();

    /**
     * 标记指定绝对路径的文件已被读取。
     *
     * @param absolutePath 文件绝对路径
     */
    public void markRead(String absolutePath) {
        readFiles.add(absolutePath);
    }

    /**
     * 判断指定绝对路径的文件是否已被读取。
     *
     * @param absolutePath 文件绝对路径
     * @return 如果文件已读取则返回 {@code true}
     */
    public boolean hasBeenRead(String absolutePath) {
        return readFiles.contains(absolutePath);
    }

    /**
     * 从工具上下文中提取文件读取跟踪器。
     *
     * @param context 工具上下文
     * @return 上下文中的跟踪器，不存在时返回 {@code null}
     */
    public static FileReadTracker from(ToolContext context) {
        if (context == null || context.extra() == null) return null;
        Object tracker = context.extra().get(CONTEXT_KEY);
        return tracker instanceof FileReadTracker frt ? frt : null;
    }
}
