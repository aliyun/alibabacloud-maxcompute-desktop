package com.aliyun.odps.agentic.session;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

/**
 * 文件系统快照跟踪器。
 *
 * <p>用于在步骤边界记录工作目录状态，并比较两次快照之间新增、修改和删除的文件。
 */
public class SnapshotTracker {

    /**
     * 某一时刻的文件系统快照。
     *
     * @param fileHashes 文件路径到内容哈希的映射
     * @param timestamp 快照时间戳
     */
    public record FileSnapshot(
        Map<String, String> fileHashes,
        long timestamp
    ) {
        /**
         * 创建空快照。
         *
         * @return 空文件快照
         */
        public static FileSnapshot empty() {
            return new FileSnapshot(Map.of(), System.currentTimeMillis());
        }
    }

    /**
     * 两次快照之间的差异。
     *
     * @param created 新增文件列表
     * @param modified 修改文件列表
     * @param deleted 删除文件列表
     */
    public record SnapshotDiff(
        List<String> created,
        List<String> modified,
        List<String> deleted
    ) {
        /**
         * 判断差异是否为空。
         *
         * @return 无差异返回 {@code true}
         */
        public boolean isEmpty() {
            return created.isEmpty() && modified.isEmpty() && deleted.isEmpty();
        }

        /**
         * 返回全部变化文件的合并列表。
         *
         * @return 全部变化文件
         */
        public List<String> allChanged() {
            List<String> all = new ArrayList<>(created);
            all.addAll(modified);
            all.addAll(deleted);
            return all;
        }
    }

    private final Path workDir;
    private FileSnapshot currentSnapshot;

    /**
     * 使用工作目录创建快照跟踪器。
     *
     * @param workDir 工作目录
     */
    public SnapshotTracker(Path workDir) {
        this.workDir = workDir;
        this.currentSnapshot = FileSnapshot.empty();
    }

    /**
     * 采集当前工作目录状态并保存为最新快照。
     *
     * @return 新生成的文件快照
     */
    public FileSnapshot track() {
        Map<String, String> hashes = new HashMap<>();
        if (workDir != null && Files.isDirectory(workDir)) {
            try {
                Files.walkFileTree(workDir, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                        Path rel = workDir.relativize(file);
                        String relPath = rel.toString().replace('\\', '/');
                        if (relPath.contains("/.git/") || relPath.contains("/node_modules/")
                            || relPath.contains("/target/") || relPath.contains("/__pycache__/")) {
                            return FileVisitResult.CONTINUE;
                        }
                        String content = Files.readString(file);
                        hashes.put(relPath, Integer.toHexString(content.hashCode()));
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                        String name = dir.getFileName().toString();
                        if (name.equals(".git") || name.equals("node_modules")
                            || name.equals("target") || name.equals("__pycache__")) {
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                        return FileVisitResult.CONTINUE;
                    }
                });
            } catch (IOException e) {
                // 快照采集采用尽力而为策略。
            }
        }
        currentSnapshot = new FileSnapshot(hashes, System.currentTimeMillis());
        return currentSnapshot;
    }

    /**
     * 计算两次快照之间的差异。
     *
     * @param before 起始快照
     * @param after 结束快照
     * @return 快照差异
     */
    public SnapshotDiff diff(FileSnapshot before, FileSnapshot after) {
        List<String> created = new ArrayList<>();
        List<String> modified = new ArrayList<>();
        List<String> deleted = new ArrayList<>();

        for (var entry : after.fileHashes().entrySet()) {
            String path = entry.getKey();
            String afterHash = entry.getValue();
            String beforeHash = before.fileHashes().get(path);
            if (beforeHash == null) {
                created.add(path);
            } else if (!beforeHash.equals(afterHash)) {
                modified.add(path);
            }
        }

        for (String path : before.fileHashes().keySet()) {
            if (!after.fileHashes().containsKey(path)) {
                deleted.add(path);
            }
        }

        return new SnapshotDiff(created, modified, deleted);
    }

    /**
     * 返回当前缓存的最新快照。
     *
     * @return 当前快照
     */
    public FileSnapshot getCurrentSnapshot() {
        return currentSnapshot;
    }
}
