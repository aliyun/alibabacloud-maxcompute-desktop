package com.aliyun.odps.agentic.patch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 补丁引擎 -- 负责解析补丁并将其应用到文件系统。
 * 支持新增、更新、删除以及移动文件，并在更新时进行模糊匹配。
 */
public class PatchEngine {

    private static final Logger log = LoggerFactory.getLogger(PatchEngine.class);

    private final PatchParser parser;

    public PatchEngine() {
        this.parser = new PatchParser();
    }

    public PatchEngine(PatchParser parser) {
        this.parser = parser;
    }

    /**
     * 将补丁文本应用到文件系统。
     *
     * @param patchText 原始补丁文本
     * @param baseDir   用于解析相对路径的基础目录
     * @return 补丁应用结果
     * @throws IOException 文件读写失败时抛出
     */
    public ApplyResult apply(String patchText, Path baseDir) throws IOException {
        List<Hunk> hunks = parser.parse(patchText);

        if (hunks.isEmpty()) {
            return ApplyResult.failure("No files were modified");
        }

        List<FileChange> changes = new ArrayList<>();

        for (Hunk hunk : hunks) {
            switch (hunk) {
                case Hunk.AddHunk add -> {
                    FileChange change = applyAdd(add, baseDir);
                    changes.add(change);
                    log.info("Added file: {}", add.path());
                }
                case Hunk.DeleteHunk delete -> {
                    FileChange change = applyDelete(delete, baseDir);
                    changes.add(change);
                    log.info("Deleted file: {}", delete.path());
                }
                case Hunk.UpdateHunk update -> {
                    FileChange change = applyUpdate(update, baseDir);
                    changes.add(change);
                    if (update.movePath() != null) {
                        log.info("Moved file: {} -> {}", update.path(), update.movePath());
                    } else {
                        log.info("Updated file: {}", update.path());
                    }
                }
            }
        }

        return ApplyResult.success(changes);
    }

    // ---- 新增文件 ----

    private FileChange applyAdd(Hunk.AddHunk hunk, Path baseDir) throws IOException {
        Path filePath = baseDir.resolve(hunk.path());
        String content = String.join("\n", hunk.contents());

        Files.createDirectories(filePath.getParent());
        Files.writeString(filePath, content, StandardCharsets.UTF_8);

        return new FileChange(filePath, null, content, ChangeType.ADD, null);
    }

    // ---- 删除文件 ----

    private FileChange applyDelete(Hunk.DeleteHunk hunk, Path baseDir) throws IOException {
        Path filePath = baseDir.resolve(hunk.path());
        String oldContent = Files.readString(filePath, StandardCharsets.UTF_8);
        Files.delete(filePath);

        return new FileChange(filePath, oldContent, null, ChangeType.DELETE, null);
    }

    // ---- 更新文件 ----

    private FileChange applyUpdate(Hunk.UpdateHunk hunk, Path baseDir) throws IOException {
        Path filePath = baseDir.resolve(hunk.path());
        String originalText = Files.readString(filePath, StandardCharsets.UTF_8);

        String newContent = deriveNewContentsFromChunks(hunk.chunks(), originalText);

        Path targetPath = filePath;
        if (hunk.movePath() != null) {
            targetPath = baseDir.resolve(hunk.movePath());
            Files.createDirectories(targetPath.getParent());
            Files.writeString(targetPath, newContent, StandardCharsets.UTF_8);
            Files.delete(filePath);
        } else {
            Files.writeString(filePath, newContent, StandardCharsets.UTF_8);
        }

        String diff = generateUnifiedDiff(originalText, newContent);
        return new FileChange(
            targetPath,
            originalText,
            newContent,
            hunk.movePath() != null ? ChangeType.MOVE : ChangeType.UPDATE,
            diff
        );
    }

    // ---- 片段应用 ----

    /**
     * 根据更新片段推导新的文件内容。
     *
     * @param chunks       更新片段列表
     * @param originalText 原始文件文本
     * @return 更新后的文件文本
     */
    String deriveNewContentsFromChunks(List<UpdateFileChunk> chunks, String originalText) {
        List<String> originalLines = splitLines(originalText);

        if (!originalLines.isEmpty() && originalLines.get(originalLines.size() - 1).isEmpty()) {
            originalLines.remove(originalLines.size() - 1);
        }

        List<Replacement> replacements = computeReplacements(originalLines, chunks);
        List<String> newLines = applyReplacements(originalLines, replacements);

        if (newLines.isEmpty() || !newLines.get(newLines.size() - 1).isEmpty()) {
            newLines.add("");
        }

        return String.join("\n", newLines);
    }

    /**
     * 计算每个更新片段对应的替换位置。
     */
    private List<Replacement> computeReplacements(List<String> originalLines, List<UpdateFileChunk> chunks) {
        List<Replacement> replacements = new ArrayList<>();
        int lineIndex = 0;

        for (UpdateFileChunk chunk : chunks) {
            if (chunk.changeContext() != null) {
                int contextIdx = seekSequence(originalLines, List.of(chunk.changeContext()), lineIndex);
                if (contextIdx == -1) {
                    throw new IllegalStateException(
                        "Failed to find context: \"" + chunk.changeContext() + "\""
                    );
                }
                lineIndex = contextIdx;
            }

            int matchIdx;
            if (chunk.oldLines().isEmpty()) {
                matchIdx = lineIndex;
            } else {
                matchIdx = seekSequence(originalLines, chunk.oldLines(), lineIndex, chunk.isEndOfFile());
                if (matchIdx == -1) {
                    throw new IllegalStateException(
                        "Failed to find old_lines in file at line " + lineIndex
                    );
                }
            }

            replacements.add(new Replacement(matchIdx, chunk.oldLines().size(), chunk.newLines()));
            lineIndex = matchIdx + chunk.oldLines().size();
        }

        return replacements;
    }

    /**
     * 应用计算好的替换列表，生成新的行列表。
     */
    private List<String> applyReplacements(List<String> originalLines, List<Replacement> replacements) {
        replacements.sort((a, b) -> Integer.compare(b.start, a.start));

        List<String> result = new ArrayList<>(originalLines);

        for (Replacement rep : replacements) {
            for (int i = 0; i < rep.count; i++) {
                result.remove(rep.start);
            }
            result.addAll(rep.start, rep.newLines);
        }

        return result;
    }

    // ---- 带模糊匹配的查找 ----

    /**
     * 在文件中查找行序列，支持四级模糊匹配。
     *
     * @param lines      文件行列表
     * @param pattern    待匹配模式
     * @param startIndex 起始搜索位置
     * @param eof        是否仅在文件末尾匹配
     * @return 匹配起始位置；未找到时返回 -1
     */
    int seekSequence(List<String> lines, List<String> pattern, int startIndex, boolean eof) {
        if (pattern.isEmpty()) return -1;

        int exact = tryMatch(lines, pattern, startIndex, String::equals, eof);
        if (exact != -1) return exact;

        int rstrip = tryMatch(lines, pattern, startIndex, (a, b) -> a.stripTrailing().equals(b.stripTrailing()), eof);
        if (rstrip != -1) return rstrip;

        int trim = tryMatch(lines, pattern, startIndex, (a, b) -> a.trim().equals(b.trim()), eof);
        if (trim != -1) return trim;

        int normalized = tryMatch(lines, pattern, startIndex,
            (a, b) -> normalizeUnicode(a.trim()).equals(normalizeUnicode(b.trim())), eof);
        return normalized;
    }

    int seekSequence(List<String> lines, List<String> pattern, int startIndex) {
        return seekSequence(lines, pattern, startIndex, false);
    }

    /**
     * 从指定位置开始尝试逐点匹配模式。
     */
    private int tryMatch(List<String> lines, List<String> pattern, int startIndex,
                         java.util.function.BiPredicate<String, String> comparator, boolean eof) {
        int limit = eof ? Math.max(startIndex, lines.size() - pattern.size()) : lines.size() - pattern.size();

        for (int i = startIndex; i <= limit; i++) {
            if (i + pattern.size() > lines.size()) break;

            boolean matches = true;
            for (int j = 0; j < pattern.size(); j++) {
                if (!comparator.test(lines.get(i + j), pattern.get(j))) {
                    matches = false;
                    break;
                }
            }
            if (matches) return i;
        }

        return -1;
    }

    /**
     * 将常见 Unicode 标点归一化为 ASCII 等价字符。
     */
    private String normalizeUnicode(String s) {
        return s
            .replace('‘', '\'')
            .replace('’', '\'')
            .replace('“', '"')
            .replace('”', '"')
            .replace('–', '-')
            .replace('—', '-')
            .replace(' ', ' ');
    }

    // ---- Unified diff 生成 ----

    /**
     * 生成简化版 unified diff。
     */
    private String generateUnifiedDiff(String oldContent, String newContent) {
        String[] oldLines = oldContent.split("\n");
        String[] newLines = newContent.split("\n");

        StringBuilder diff = new StringBuilder();
        diff.append("@@ -1 +1 @@\n");

        boolean hasChanges = false;
        int maxLen = Math.max(oldLines.length, newLines.length);

        for (int i = 0; i < maxLen; i++) {
            String oldLine = i < oldLines.length ? oldLines[i] : "";
            String newLine = i < newLines.length ? newLines[i] : "";

            if (!oldLine.equals(newLine)) {
                if (!oldLine.isEmpty()) diff.append("-").append(oldLine).append("\n");
                if (!newLine.isEmpty()) diff.append("+").append(newLine).append("\n");
                hasChanges = true;
            } else if (!oldLine.isEmpty()) {
                diff.append(" ").append(oldLine).append("\n");
            }
        }

        return hasChanges ? diff.toString() : "";
    }

    // ---- 工具方法 ----

    /**
     * 将文本拆分为行，并保留末尾空行。
     */
    private List<String> splitLines(String content) {
        if (content.isEmpty()) return new ArrayList<>();
        List<String> lines = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < content.length(); i++) {
            if (content.charAt(i) == '\n') {
                lines.add(content.substring(start, i));
                start = i + 1;
            }
        }
        if (start <= content.length()) {
            lines.add(content.substring(start));
        }
        return lines;
    }

    /**
     * 内部替换记录。
     */
    private record Replacement(int start, int count, List<String> newLines) {}
}
