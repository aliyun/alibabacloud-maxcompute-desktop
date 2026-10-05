package com.aliyun.odps.agentic.session;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.Semaphore;

/**
 * 基于 Git 的快照策略。
 *
 * <p>该实现使用独立的 Git 目录跟踪工作区状态，支持快照采集、恢复、差异计算和回滚，是首选的快照实现。
 */
public class GitSnapshotStrategy implements RevertService.SnapshotStrategy {

    private static final Logger log = LoggerFactory.getLogger(GitSnapshotStrategy.class);

    private final Path workDir;
    private final Path gitdir;
    private final Semaphore lock = new Semaphore(1);
    private boolean initialized = false;
    private Boolean gitAvailable = null;

    /**
     * 使用工作目录和数据目录创建 Git 快照策略。
     *
     * @param workDir 工作目录
     * @param dataDir 数据目录
     */
    public GitSnapshotStrategy(Path workDir, Path dataDir) {
        this.workDir = workDir;
        this.gitdir = dataDir.resolve("snapshot").resolve(Integer.toHexString(workDir.toString().hashCode()));
    }

    /**
     * 检查系统中是否可用 Git。
     *
     * @return 可用返回 {@code true}
     */
    @Override
    public boolean isAvailable() {
        if (gitAvailable != null) return gitAvailable;
        try {
            ProcessBuilder pb = new ProcessBuilder("git", "--version");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            int code = p.waitFor();
            gitAvailable = (code == 0);
            if (gitAvailable) {
                log.info("git detected — using GitSnapshotStrategy");
            } else {
                log.info("git not available — fall back to FileHashSnapshotStrategy");
            }
        } catch (Exception e) {
            gitAvailable = false;
            log.info("git not available — fall back to FileHashSnapshotStrategy");
        }
        return gitAvailable;
    }

    /**
     * 初始化独立 Git 快照仓库。
     */
    @Override
    public void init() throws Exception {
        if (!isAvailable()) return;
        if (initialized) return;

        lock.acquire();
        try {
            if (Files.exists(gitdir) && Files.isDirectory(gitdir)) {
                initialized = true;
                return;
            }
            Files.createDirectories(gitdir);

            git("init", Map.of("GIT_DIR", gitdir.toString(), "GIT_WORK_TREE", workDir.toString()));
            git(List.of("--git-dir", gitdir.toString(), "config", "core.autocrlf", "false"));
            git(List.of("--git-dir", gitdir.toString(), "config", "core.longpaths", "true"));
            git(List.of("--git-dir", gitdir.toString(), "config", "core.symlinks", "true"));

            log.info("initialized git snapshot at {}", gitdir);
            initialized = true;
        } finally {
            lock.release();
        }
    }

    /**
     * 采集当前工作树快照并返回树哈希。
     *
     * @return 快照哈希
     */
    @Override
    public Optional<String> track() throws Exception {
        if (!isAvailable()) return Optional.empty();
        ensureInit();

        lock.acquire();
        try {
            List<String> args = baseArgs();
            args.add("add");
            args.add("--all");
            git(args);

            List<String> writeTreeArgs = baseArgs();
            writeTreeArgs.add("write-tree");
            String result = git(writeTreeArgs);
            String hash = result.trim();

            log.debug("tracked snapshot: {}", hash);
            return Optional.of(hash);
        } finally {
            lock.release();
        }
    }

    /**
     * 获取快照中变化文件列表。
     *
     * @param hash 快照哈希
     * @return 快照补丁信息
     */
    @Override
    public RevertService.SnapshotPatch patch(String hash) throws Exception {
        if (!isAvailable()) return null;

        List<String> args = baseArgs();
        args.addAll(List.of("diff-tree", "--no-commit-id", "--name-only", "-r", hash));
        String result = git(args);
        List<String> files = Arrays.stream(result.split("\n"))
            .filter(line -> !line.isBlank())
            .toList();
        return new RevertService.SnapshotPatch(hash, files);
    }

    /**
     * 将工作目录恢复到指定快照。
     *
     * @param snapshot 快照哈希
     */
    @Override
    public void restore(String snapshot) throws Exception {
        if (!isAvailable()) return;

        lock.acquire();
        try {
            List<String> readTreeArgs = baseArgs();
            readTreeArgs.addAll(List.of("read-tree", snapshot));
            git(readTreeArgs);

            List<String> checkoutArgs = baseArgs();
            checkoutArgs.addAll(List.of("checkout-index", "-f", "-a"));
            git(checkoutArgs);

            log.debug("restored snapshot: {}", snapshot);
        } finally {
            lock.release();
        }
    }

    /**
     * 回滚补丁对应的文件变化。
     *
     * @param patches 待回滚补丁列表
     */
    @Override
    public void revert(List<RevertService.SnapshotPatch> patches) throws Exception {
        if (!isAvailable() || patches.isEmpty()) return;

        List<String> allFiles = patches.stream()
            .flatMap(p -> p.files().stream())
            .distinct()
            .toList();

        if (allFiles.isEmpty()) return;

        List<String> args = baseArgs();
        args.add("checkout");
        args.add("HEAD");
        args.add("--");
        args.addAll(allFiles);
        git(args);
    }

    /**
     * 生成指定快照的差异文本。
     *
     * @param hash 快照哈希
     * @return 统一 diff 文本
     */
    @Override
    public String diff(String hash) throws Exception {
        if (!isAvailable()) return "";

        List<String> args = baseArgs();
        args.addAll(List.of("diff-tree", "-p", hash));
        return git(args);
    }

    /**
     * 对内部 Git 仓库执行清理。
     */
    @Override
    public void cleanup() throws Exception {
        if (!isAvailable()) return;

        List<String> args = baseArgs();
        args.addAll(List.of("gc", "--prune=7.days"));
        try {
            git(args);
        } catch (Exception e) {
            log.warn("snapshot cleanup failed", e);
        }
    }

    // ── 辅助方法 ──

    private List<String> baseArgs() {
        return new ArrayList<>(List.of(
            "--git-dir", gitdir.toString(),
            "--work-tree", workDir.toString()
        ));
    }

    private String git(List<String> args) throws Exception {
        return git(args, Map.of());
    }

    private String git(List<String> args, Map<String, String> env) throws Exception {
        List<String> fullCmd = new ArrayList<>();
        fullCmd.add("git");
        fullCmd.addAll(args);

        ProcessBuilder pb = new ProcessBuilder(fullCmd);
        pb.directory(workDir.toFile());
        pb.redirectErrorStream(true);
        env.forEach(pb.environment()::put);

        Process p = pb.start();
        String output = new String(p.getInputStream().readAllBytes());
        int code = p.waitFor();

        if (code != 0) {
            throw new RuntimeException("git command failed (exit " + code + "): git " +
                String.join(" ", args) + "\n" + output);
        }
        return output;
    }

    private void git(String command, Map<String, String> env) throws Exception {
        git(Arrays.asList(command.split(" ")), env);
    }

    private void ensureInit() throws Exception {
        if (!initialized) init();
    }
}
