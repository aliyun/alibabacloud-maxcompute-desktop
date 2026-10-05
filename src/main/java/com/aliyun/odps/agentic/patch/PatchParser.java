package com.aliyun.odps.agentic.patch;

import java.util.ArrayList;
import java.util.List;

/**
 * 补丁解析器 -- 将自定义补丁格式解析为 {@link Hunk} 对象。
 * 支持新增、删除、更新文件以及更新片段的解析。
 */
public class PatchParser {

    /**
     * 将补丁文本解析为补丁块列表。
     *
     * @param patchText 原始补丁文本
     * @return 解析得到的补丁块列表
     * @throws IllegalArgumentException 补丁格式非法时抛出
     */
    public List<Hunk> parse(String patchText) {
        String cleaned = stripHeredoc(patchText.trim());
        String[] lines = cleaned.split("\n");

        String beginMarker = "*** Begin Patch";
        String endMarker = "*** End Patch";

        int beginIdx = -1;
        int endIdx = -1;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].trim().equals(beginMarker)) beginIdx = i;
            if (lines[i].trim().equals(endMarker)) endIdx = i;
        }

        if (beginIdx == -1 || endIdx == -1 || beginIdx >= endIdx) {
            throw new IllegalArgumentException("Invalid patch format: missing Begin/End markers");
        }

        List<Hunk> hunks = new ArrayList<>();
        int i = beginIdx + 1;

        while (i < endIdx) {
            ParsedHeader header = parsePatchHeader(lines, i);
            if (header == null) {
                i++;
                continue;
            }

            if (header.type == HeaderType.ADD) {
                ParsedContent content = parseAddFileContent(lines, header.nextIdx);
                hunks.add(new Hunk.AddHunk(header.filePath, splitLines(content.content)));
                i = content.nextIdx;
            } else if (header.type == HeaderType.DELETE) {
                hunks.add(new Hunk.DeleteHunk(header.filePath));
                i = header.nextIdx;
            } else if (header.type == HeaderType.UPDATE) {
                ParsedChunks chunks = parseUpdateFileChunks(lines, header.nextIdx);
                hunks.add(new Hunk.UpdateHunk(header.filePath, header.movePath, chunks.chunks));
                i = chunks.nextIdx;
            } else {
                i++;
            }
        }

        return hunks;
    }

    // ---- 头部类型 ----

    private enum HeaderType { ADD, DELETE, UPDATE }

    private record ParsedHeader(HeaderType type, String filePath, String movePath, int nextIdx) {}

    /**
     * 解析补丁头部行，如 Add/Delete/Update File 指令。
     */
    private ParsedHeader parsePatchHeader(String[] lines, int startIdx) {
        String line = lines[startIdx];

        if (line.startsWith("*** Add File:")) {
            String filePath = line.substring("*** Add File:".length()).trim();
            return filePath.isEmpty() ? null : new ParsedHeader(HeaderType.ADD, filePath, null, startIdx + 1);
        }

        if (line.startsWith("*** Delete File:")) {
            String filePath = line.substring("*** Delete File:".length()).trim();
            return filePath.isEmpty() ? null : new ParsedHeader(HeaderType.DELETE, filePath, null, startIdx + 1);
        }

        if (line.startsWith("*** Update File:")) {
            String filePath = line.substring("*** Update File:".length()).trim();
            String movePath = null;
            int nextIdx = startIdx + 1;

            if (nextIdx < lines.length && lines[nextIdx].startsWith("*** Move to:")) {
                movePath = lines[nextIdx].substring("*** Move to:".length()).trim();
                nextIdx++;
            }

            return filePath.isEmpty() ? null : new ParsedHeader(HeaderType.UPDATE, filePath, movePath, nextIdx);
        }

        return null;
    }

    // ---- 片段解析 ----

    private record ParsedChunks(List<UpdateFileChunk> chunks, int nextIdx) {}

    /**
     * 解析更新文件中的各个 {@code @@} 片段。
     */
    private ParsedChunks parseUpdateFileChunks(String[] lines, int startIdx) {
        List<UpdateFileChunk> chunks = new ArrayList<>();
        int i = startIdx;

        while (i < lines.length && !lines[i].startsWith("***")) {
            if (lines[i].startsWith("@@")) {
                String contextLine = lines[i].substring(2).trim();
                if (contextLine.endsWith("@@")) {
                    contextLine = contextLine.substring(0, contextLine.length() - 2).trim();
                }
                i++;

                List<String> oldLines = new ArrayList<>();
                List<String> newLines = new ArrayList<>();
                boolean isEndOfFile = false;

                while (i < lines.length && !lines[i].startsWith("@@") && !lines[i].startsWith("***")) {
                    String changeLine = lines[i];

                    if (changeLine.equals("*** End of File")) {
                        isEndOfFile = true;
                        i++;
                        break;
                    }

                    if (changeLine.startsWith(" ")) {
                        String content = changeLine.substring(1);
                        oldLines.add(content);
                        newLines.add(content);
                    } else if (changeLine.startsWith("-")) {
                        oldLines.add(changeLine.substring(1));
                    } else if (changeLine.startsWith("+")) {
                        newLines.add(changeLine.substring(1));
                    }

                    i++;
                }

                chunks.add(new UpdateFileChunk(
                    oldLines,
                    newLines,
                    contextLine.isEmpty() ? null : contextLine,
                    isEndOfFile
                ));
            } else {
                i++;
            }
        }

        return new ParsedChunks(chunks, i);
    }

    // ---- 新增文件内容解析 ----

    private record ParsedContent(String content, int nextIdx) {}

    /**
     * 解析新增文件内容，只收集以 {@code +} 开头的行。
     */
    private ParsedContent parseAddFileContent(String[] lines, int startIdx) {
        StringBuilder content = new StringBuilder();
        int i = startIdx;

        while (i < lines.length && !lines[i].startsWith("***")) {
            if (lines[i].startsWith("+")) {
                content.append(lines[i].substring(1)).append("\n");
            }
            i++;
        }

        String result = content.toString();
        if (result.endsWith("\n")) {
            result = result.substring(0, result.length() - 1);
        }

        return new ParsedContent(result, i);
    }

    // ---- 工具方法 ----

    /**
     * 若存在 heredoc 包装，则去除外层包装文本。
     */
    private String stripHeredoc(String input) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
            "^(?:cat\\s+)?<<['\"]?(\\w+)['\"]?\\s*\\n([\\s\\S]*?)\\n\\1\\s*$"
        ).matcher(input);
        if (m.find()) {
            return m.group(2);
        }
        return input;
    }

    /**
     * 将文本拆分为行，并保留空行信息。
     */
    private List<String> splitLines(String content) {
        if (content.isEmpty()) return List.of();
        List<String> lines = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < content.length(); i++) {
            if (content.charAt(i) == '\n') {
                lines.add(content.substring(start, i));
                start = i + 1;
            }
        }
        if (start < content.length()) {
            lines.add(content.substring(start));
        }
        return lines;
    }
}
