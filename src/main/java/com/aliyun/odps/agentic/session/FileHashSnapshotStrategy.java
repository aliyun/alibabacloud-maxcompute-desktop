package com.aliyun.odps.agentic.session;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 基于文件内容哈希的快照策略。
 *
 * <p>当 Git 不可用时，该实现通过记录文件哈希与内容来提供退化版快照、恢复和差异比较能力。
 */
public class FileHashSnapshotStrategy implements RevertService.SnapshotStrategy {

    private static final Logger log = LoggerFactory.getLogger(FileHashSnapshotStrategy.class);

    private static final Set<String> SKIP_DIRS = Set.of(
        ".git", "node_modules", "target", "__pycache__", ".idea", ".vscode", "build", "dist"
    );

    private final Path workDir;
    private final Map<String, Map<String, String>> snapshots = new ConcurrentHashMap<>();
    private final Map<String, Map<String, byte[]>> snapshotContents = new ConcurrentHashMap<>();

    /**
     * 使用工作目录创建快照策略。
     *
     * @param workDir 工作目录
     */
    public FileHashSnapshotStrategy(Path workDir) {
        this.workDir = workDir;
    }

    /**
     * 判断当前策略是否可用。
     *
     * @return 可用返回 {@code true}
     */
    @Override
    public boolean isAvailable() {
        return workDir != null && Files.isDirectory(workDir);
    }

    /**
     * 初始化快照策略。
     */
    @Override
    public void init() throws Exception {
        log.info("using FileHashSnapshotStrategy (no git dependency)");
    }

    /**
     * 采集当前目录的文件快照。
     *
     * @return 快照 ID
     */
    @Override
    public Optional<String> track() throws Exception {
        if (!isAvailable()) return Optional.empty();

        Map<String, String> fileHashes = new LinkedHashMap<>();
        Map<String, byte[]> fileContents = new LinkedHashMap<>();

        Files.walkFileTree(workDir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                String name = dir.getFileName().toString();
                return SKIP_DIRS.contains(name) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Path rel = workDir.relativize(file);
                String relPath = rel.toString().replace('\\', '/');
                try {
                    byte[] content = Files.readAllBytes(file);
                    String hash = Integer.toHexString(Arrays.hashCode(content));
                    fileHashes.put(relPath, hash);
                    fileContents.put(relPath, content);
                } catch (IOException e) {
                    // 跳过不可读取文件。
                }
                return FileVisitResult.CONTINUE;
            }
        });

        String snapshotId = "snap-" + Integer.toHexString(fileHashes.hashCode());
        snapshots.put(snapshotId, fileHashes);
        snapshotContents.put(snapshotId, fileContents);

        log.debug("tracked file-hash snapshot: {} ({} files)", snapshotId, fileHashes.size());
        return Optional.of(snapshotId);
    }

    /**
     * 获取快照包含的文件列表。
     *
     * @param hash 快照 ID
     * @return 快照补丁信息
     */
    @Override
    public RevertService.SnapshotPatch patch(String hash) throws Exception {
        Map<String, String> files = snapshots.get(hash);
        if (files == null) return new RevertService.SnapshotPatch(hash, List.of());
        return new RevertService.SnapshotPatch(hash, new ArrayList<>(files.keySet()));
    }

    /**
     * 按快照内容恢复文件系统。
     *
     * @param snapshot 快照 ID
     */
    @Override
    public void restore(String snapshot) throws Exception {
        Map<String, byte[]> contents = snapshotContents.get(snapshot);
        if (contents == null) return;

        for (Map.Entry<String, byte[]> entry : contents.entrySet()) {
            Path filePath = workDir.resolve(entry.getKey());
            try {
                Files.createDirectories(filePath.getParent());
                Files.write(filePath, entry.getValue());
            } catch (IOException e) {
                log.warn("failed to restore file: {}", entry.getKey(), e);
            }
        }
        log.debug("restored file-hash snapshot: {}", snapshot);
    }

    /**
     * 回滚给定补丁集合。
     * 当前实现通过恢复最早快照实现整体验证回退。
     *
     * @param patches 待回滚补丁列表
     */
    @Override
    public void revert(List<RevertService.SnapshotPatch> patches) throws Exception {
        if (patches.isEmpty()) return;
        String hash = patches.get(0).hash();
        restore(hash);
    }

    /**
     * 生成当前目录与指定快照之间的差异摘要。
     *
     * @param hash 快照 ID
     * @return 类似 diff 的文本摘要
     */
    @Override
    public String diff(String hash) throws Exception {
        Map<String, String> current = new LinkedHashMap<>();
        if (Files.isDirectory(workDir)) {
            Files.walkFileTree(workDir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    String name = dir.getFileName().toString();
                    return SKIP_DIRS.contains(name) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Path rel = workDir.relativize(file);
                    String relPath = rel.toString().replace('\\', '/');
                    try {
                        byte[] content = Files.readAllBytes(file);
                        current.put(relPath, Integer.toHexString(Arrays.hashCode(content)));
                    } catch (IOException e) {
                        // 跳过不可读取文件。
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        }

        Map<String, String> snapshot = snapshots.getOrDefault(hash, Map.of());

        StringBuilder sb = new StringBuilder();
        Set<String> allFiles = new LinkedHashSet<>();
        allFiles.addAll(current.keySet());
        allFiles.addAll(snapshot.keySet());

        for (String file : allFiles) {
            String currentHash = current.get(file);
            String snapshotHash = snapshot.get(file);
            if (!Objects.equals(currentHash, snapshotHash)) {
                if (snapshotHash == null) {
                    sb.append("diff --git a/").append(file).append(" b/").append(file).append("\n");
                    sb.append("new file\n");
                } else if (currentHash == null) {
                    sb.append("diff --git a/").append(file).append(" b/").append(file).append("\n");
                    sb.append("deleted file\n");
                } else {
                    sb.append("diff --git a/").append(file).append(" b/").append(file).append("\n");
                    sb.append("modified\n");
                }
            }
        }
        return sb.toString();
    }

    /**
     * 清理旧快照，仅保留最近的有限数量。
     */
    @Override
    public void cleanup() throws Exception {
        if (snapshots.size() > 10) {
            List<String> keys = new ArrayList<>(snapshots.keySet());
            for (int i = 0; i < keys.size() - 10; i++) {
                String key = keys.get(i);
                snapshots.remove(key);
                snapshotContents.remove(key);
            }
        }
    }
}
