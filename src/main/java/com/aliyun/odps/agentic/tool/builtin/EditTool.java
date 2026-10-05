package com.aliyun.odps.agentic.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.aliyun.odps.agentic.tool.FileReadTracker;
import com.aliyun.odps.agentic.tool.ToolContext;
import com.aliyun.odps.agentic.tool.ToolDef;
import com.aliyun.odps.agentic.tool.ToolResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 内置编辑工具，用于对文件执行精确替换。
 * 当精确匹配失败时，会依次尝试多种模糊匹配策略，以提高文本替换成功率。
 *
 * <p>工具同时支持唯一替换和 {@code replaceAll} 批量替换，并对未读取文件施加写入保护。
 */
public class EditTool implements ToolDef {

    private static final String ID = "edit";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String DESCRIPTION = ResourceLoader.load("tools/edit.txt");

    /**
     * Similarity thresholds for block anchor fallback matching.
     * 用于块锚点回退匹配。
     */
    private static final double SINGLE_CANDIDATE_SIMILARITY_THRESHOLD = 0.65;
    private static final double MULTIPLE_CANDIDATES_SIMILARITY_THRESHOLD = 0.65;

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public String getDescription() {
        return DESCRIPTION;
    }

    /**
     * 返回 edit 工具的参数 Schema。
     *
     * <p>对应参数示例：
     * <pre>{@code
     * {
     *   "filePath": "/abs/path/App.java",
     *   "oldString": "old text",
     *   "newString": "new text",
     *   "replaceAll": false
     * }
     * }</pre>
     */
    @Override
    public ObjectNode getParametersSchema() {
        // 参数 Schema 定义
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");

        ObjectNode properties = MAPPER.createObjectNode();

        ObjectNode filePath = MAPPER.createObjectNode();
        filePath.put("type", "string");
        filePath.put("description", "The absolute path to the file to modify");
        properties.set("filePath", filePath);

        ObjectNode oldString = MAPPER.createObjectNode();
        oldString.put("type", "string");
        oldString.put("description", "The text to replace");
        properties.set("oldString", oldString);

        ObjectNode newString = MAPPER.createObjectNode();
        newString.put("type", "string");
        newString.put("description", "The text to replace it with (must be different from oldString)");
        properties.set("newString", newString);

        ObjectNode replaceAll = MAPPER.createObjectNode();
        replaceAll.put("type", "boolean");
        replaceAll.put("description", "Replace all occurrences of oldString (default false)");
        properties.set("replaceAll", replaceAll);

        schema.set("properties", properties);

        var required = MAPPER.createArrayNode();
        required.add("filePath");
        required.add("oldString");
        required.add("newString");
        schema.set("required", required);

        return schema;
    }

    /**
     * 执行文件编辑。
     *
     * <p>执行流程：
     * <ol>
     *   <li>提取参数 {@code filePath}、{@code oldString}、{@code newString}、{@code replaceAll}</li>
     *   <li>若 {@code oldString} 为空且文件不存在，则创建新文件并写入 {@code newString}</li>
     *   <li>对已存在文件，检查是否已通过 ReadTool 读取过（先读后写保护）</li>
     *   <li>在文件内容中查找 {@code oldString}，支持 9 级模糊匹配策略
     *       （精确 → 行裁剪 → 块锚点 → 空白归一化 → 缩进灵活 → 转义归一化 → 边界裁剪 → 上下文感知 → 多次匹配）</li>
     *   <li>通过 {@link ToolContext#permissionAsker()} 请求编辑权限</li>
     *   <li>将替换结果写回文件</li>
     * </ol>
     */
    @Override
    public ToolResult execute(JsonNode args, ToolContext context) {
        String filePath = ToolDef.extractString(args, "filePath", "file_path");
        String oldString = ToolDef.extractString(args, "oldString", "old_string");
        String newString = ToolDef.extractString(args, "newString", "new_string");
        boolean doReplaceAll = (args.has("replaceAll") && args.get("replaceAll").asBoolean(false))
            || (args.has("replace_all") && args.get("replace_all").asBoolean(false));

        // 参数校验
        if (filePath == null || filePath.isEmpty()) {
            return ToolResult.error("filePath is required");
        }
        if (oldString == null) {
            return ToolResult.error("oldString is required");
        }
        if (newString == null) {
            return ToolResult.error("newString is required");
        }

        // oldString 和 newString 相同时无变更
        if (oldString.equals(newString)) {
            return ToolResult.error("No changes to apply: oldString and newString are identical.");
        }

        try {
            Path path = Path.of(filePath);

            // 当 oldString 为空时：若文件不存在则创建
            if (oldString.isEmpty()) {
                boolean existed = Files.exists(path);
                if (existed) {
                    return ToolResult.error(
                        "oldString cannot be empty when editing an existing file. " +
                        "Provide the exact text to replace, or use write for an intentional full-file replacement.");
                }
                String contentNew = newString;

                // Permission check
                if (context.permissionAsker() != null) {
                    String relativePath = path.getFileName().toString();
                    boolean allowed = context.permissionAsker().ask("edit", relativePath, "Edit " + relativePath);
                    if (!allowed) {
                        return ToolResult.error("Permission denied for editing: " + filePath);
                    }
                }

                Files.createDirectories(path.getParent());
                Files.writeString(path, contentNew);

                return ToolResult.of(
                    path.getFileName().toString(),
                    "Edit applied successfully."
                );
            }

            if (!Files.exists(path)) {
                // 文件不存在
                return ToolResult.error("File " + filePath + " not found");
            }

            if (Files.isDirectory(path)) {
                // 目标为目录而非文件
                return ToolResult.error("Path is a directory, not a file: " + filePath);
            }

            // Read-before-write guard
            FileReadTracker tracker = FileReadTracker.from(context);
            if (tracker != null && !tracker.hasBeenRead(path.toAbsolutePath().toString())) {
                return ToolResult.error(
                    "You must read this file before editing it. Use the Read tool first.");
            }

            String content = Files.readString(path);

            // 执行替换函数
            String newContent;
            try {
                newContent = replace(content, oldString, newString, doReplaceAll);
            } catch (EditException e) {
                return ToolResult.error(e.getMessage());
            }

            // Permission check
            if (context.permissionAsker() != null) {
                String relativePath = path.getFileName().toString();
                boolean allowed = context.permissionAsker().ask("edit", relativePath, "Edit " + relativePath);
                if (!allowed) {
                    return ToolResult.error("Permission denied for editing: " + filePath);
                }
            }

            Files.writeString(path, newContent);

            return ToolResult.of(
                path.getFileName().toString(),
                "Edit applied successfully."
            );

        } catch (IOException e) {
            return ToolResult.error("Failed to edit file: " + e.getMessage());
        }
    }

    // ========================= 替换函数 ===========================

    /**
     * 使用完整替换器链在内容中替换 {@code oldString}。
     * 替换器按顺序尝试，首次唯一匹配即应用。
     *
     * @param content    文件内容
     * @param oldString  待查找的文本
     * @param newString  替换后的文本
     * @param replaceAll 是否替换所有匹配项
     * @return 替换后的新内容
     * @throws EditException 未找到匹配或存在多个匹配且未启用 {@code replaceAll}
     */
    static String replace(String content, String oldString, String newString, boolean replaceAll) {
        if (oldString.equals(newString)) {
            throw new EditException("No changes to apply: oldString and newString are identical.");
        }
        if (oldString.isEmpty()) {
            throw new EditException(
                "oldString cannot be empty when editing an existing file. " +
                "Provide the exact text to replace, or use write for an intentional full-file replacement.");
        }

        boolean notFound = true;

        // 按顺序遍历替换器链
        @SuppressWarnings("unchecked")
        Replacer[] replacers = {
            EditTool::simpleReplacer,
            EditTool::lineTrimmedReplacer,
            EditTool::blockAnchorReplacer,
            EditTool::whitespaceNormalizedReplacer,
            EditTool::indentationFlexibleReplacer,
            EditTool::escapeNormalizedReplacer,
            EditTool::trimmedBoundaryReplacer,
            EditTool::contextAwareReplacer,
            EditTool::multiOccurrenceReplacer,
        };

        for (Replacer replacer : replacers) {
            List<String> matches = replacer.find(content, oldString);
            for (String search : matches) {
                int index = content.indexOf(search);
                if (index == -1) continue;
                notFound = false;
                if (isDisproportionateMatch(search, oldString)) {
                    throw new EditException(
                        "Refusing replacement because the matched span is much larger than oldString. " +
                        "Re-read the file and provide the full exact oldString for the intended replacement.");
                }

                if (replaceAll) {
                    return content.replace(search, newString);
                }

                int lastIndex = content.lastIndexOf(search);
                if (index != lastIndex) continue; // multiple matches, skip

                return content.substring(0, index) + newString + content.substring(index + search.length());
            }
        }

        if (notFound) {
            throw new EditException(
                "Could not find oldString in the file. It must match exactly, " +
                "including whitespace, indentation, and line endings.");
        }
        throw new EditException(
            "Found multiple matches for oldString. Provide more surrounding context to make the match unique.");
    }

    // ========================= 替换器链 ==================================

    @FunctionalInterface
    interface Replacer {
        List<String> find(String content, String find);
    }

    /**
     * 精确匹配替换器。
     */
    static List<String> simpleReplacer(String content, String find) {
        List<String> results = new ArrayList<>();
        if (content.contains(find)) {
            results.add(find);
        }
        return results;
    }

    /**
     * 行首尾空白裁剪后匹配的替换器。
     */
    static List<String> lineTrimmedReplacer(String content, String find) {
        List<String> results = new ArrayList<>();
        String[] originalLines = content.split("\n", -1);
        String[] searchLines = find.split("\n", -1);

        // 移除末尾空行
        if (searchLines.length > 0 && searchLines[searchLines.length - 1].isEmpty()) {
            String[] trimmed = new String[searchLines.length - 1];
            System.arraycopy(searchLines, 0, trimmed, 0, trimmed.length);
            searchLines = trimmed;
        }

        if (searchLines.length == 0) return results;

        for (int i = 0; i <= originalLines.length - searchLines.length; i++) {
            boolean matches = true;
            for (int j = 0; j < searchLines.length; j++) {
                if (!originalLines[i + j].trim().equals(searchLines[j].trim())) {
                    matches = false;
                    break;
                }
            }
            if (matches) {
                // Calculate the actual substring in the original content
                int matchStartIndex = 0;
                for (int k = 0; k < i; k++) {
                    matchStartIndex += originalLines[k].length() + 1;
                }
                int matchEndIndex = matchStartIndex;
                for (int k = 0; k < searchLines.length; k++) {
                    matchEndIndex += originalLines[i + k].length();
                    if (k < searchLines.length - 1) {
                        matchEndIndex += 1; // newline
                    }
                }
                results.add(content.substring(matchStartIndex, matchEndIndex));
            }
        }
        return results;
    }

    /**
     * 块锚点替换器，以首尾行作为锚点并进行相似度校验。
     */
    static List<String> blockAnchorReplacer(String content, String find) {
        List<String> results = new ArrayList<>();
        String[] originalLines = content.split("\n", -1);
        String[] searchLines = find.split("\n", -1);

        if (searchLines.length < 3) return results;

        // Remove trailing empty line if present
        if (searchLines[searchLines.length - 1].isEmpty()) {
            String[] trimmed = new String[searchLines.length - 1];
            System.arraycopy(searchLines, 0, trimmed, 0, trimmed.length);
            searchLines = trimmed;
        }

        String firstLineSearch = searchLines[0].trim();
        String lastLineSearch = searchLines[searchLines.length - 1].trim();
        int searchBlockSize = searchLines.length;
        int maxLineDelta = Math.max(1, (int) Math.floor(searchBlockSize * 0.25));

        // Collect all candidate positions where both anchors match
        record Candidate(int startLine, int endLine) {}
        List<Candidate> candidates = new ArrayList<>();

        for (int i = 0; i < originalLines.length; i++) {
            if (!originalLines[i].trim().equals(firstLineSearch)) continue;
            for (int j = i + 2; j < originalLines.length; j++) {
                if (originalLines[j].trim().equals(lastLineSearch)) {
                    int actualBlockSize = j - i + 1;
                    if (Math.abs(actualBlockSize - searchBlockSize) <= maxLineDelta) {
                        candidates.add(new Candidate(i, j));
                    }
                    break; // only first occurrence of last line
                }
            }
        }

        if (candidates.isEmpty()) return results;

        if (candidates.size() == 1) {
            Candidate c = candidates.getFirst();
            int actualBlockSize = c.endLine - c.startLine + 1;
            double similarity = 0;
            int linesToCheck = Math.min(searchBlockSize - 2, actualBlockSize - 2);

            if (linesToCheck > 0) {
                for (int j = 1; j < searchBlockSize - 1 && j < actualBlockSize - 1; j++) {
                    String originalLine = originalLines[c.startLine + j].trim();
                    String searchLine = searchLines[j].trim();
                    int maxLen = Math.max(originalLine.length(), searchLine.length());
                    if (maxLen == 0) continue;
                    int distance = levenshtein(originalLine, searchLine);
                    similarity += (1.0 - (double) distance / maxLen) / linesToCheck;
                    if (similarity >= SINGLE_CANDIDATE_SIMILARITY_THRESHOLD) break;
                }
            } else {
                similarity = 1.0;
            }

            if (similarity >= SINGLE_CANDIDATE_SIMILARITY_THRESHOLD) {
                results.add(extractBlock(content, originalLines, c.startLine, c.endLine));
            }
            return results;
        }

        // Multiple candidates — find best match
        Candidate bestMatch = null;
        double maxSimilarity = -1;

        for (Candidate candidate : candidates) {
            int actualBlockSize = candidate.endLine - candidate.startLine + 1;
            double similarity = 0;
            int linesToCheck = Math.min(searchBlockSize - 2, actualBlockSize - 2);

            if (linesToCheck > 0) {
                for (int j = 1; j < searchBlockSize - 1 && j < actualBlockSize - 1; j++) {
                    String originalLine = originalLines[candidate.startLine + j].trim();
                    String searchLine = searchLines[j].trim();
                    int maxLen = Math.max(originalLine.length(), searchLine.length());
                    if (maxLen == 0) continue;
                    int distance = levenshtein(originalLine, searchLine);
                    similarity += 1.0 - (double) distance / maxLen;
                }
                similarity /= linesToCheck;
            } else {
                similarity = 1.0;
            }

            if (similarity > maxSimilarity) {
                maxSimilarity = similarity;
                bestMatch = candidate;
            }
        }

        if (maxSimilarity >= MULTIPLE_CANDIDATES_SIMILARITY_THRESHOLD && bestMatch != null) {
            results.add(extractBlock(content, originalLines, bestMatch.startLine, bestMatch.endLine));
        }

        return results;
    }

    /**
     * 空白符归一化替换器，折叠空白后匹配。
     */
    static List<String> whitespaceNormalizedReplacer(String content, String find) {
        List<String> results = new ArrayList<>();
        String normalizedFind = normalizeWhitespace(find);

        // Handle single line matches
        String[] lines = content.split("\n", -1);
        for (String line : lines) {
            if (normalizeWhitespace(line).equals(normalizedFind)) {
                results.add(line);
            } else {
                String normalizedLine = normalizeWhitespace(line);
                if (normalizedLine.contains(normalizedFind)) {
                    String[] words = find.trim().split("\\s+");
                    if (words.length > 0) {
                        StringBuilder patternSb = new StringBuilder();
                        for (int w = 0; w < words.length; w++) {
                            if (w > 0) patternSb.append("\\s+");
                            patternSb.append(Pattern.quote(words[w]));
                        }
                        try {
                            Matcher m = Pattern.compile(patternSb.toString()).matcher(line);
                            if (m.find()) {
                                results.add(m.group());
                            }
                        } catch (Exception ignored) {
                            // Invalid regex, skip
                        }
                    }
                }
            }
        }

        // Handle multi-line matches
        String[] findLines = find.split("\n", -1);
        if (findLines.length > 1) {
            for (int i = 0; i <= lines.length - findLines.length; i++) {
                StringBuilder block = new StringBuilder();
                for (int j = 0; j < findLines.length; j++) {
                    if (j > 0) block.append("\n");
                    block.append(lines[i + j]);
                }
                if (normalizeWhitespace(block.toString()).equals(normalizedFind)) {
                    results.add(block.toString());
                }
            }
        }

        return results;
    }

    /**
     * 缩进灵活替换器，忽略公共缩进差异后匹配。
     */
    static List<String> indentationFlexibleReplacer(String content, String find) {
        List<String> results = new ArrayList<>();
        String normalizedFind = removeIndentation(find);
        String[] contentLines = content.split("\n", -1);
        String[] findLines = find.split("\n", -1);

        for (int i = 0; i <= contentLines.length - findLines.length; i++) {
            StringBuilder block = new StringBuilder();
            for (int j = 0; j < findLines.length; j++) {
                if (j > 0) block.append("\n");
                block.append(contentLines[i + j]);
            }
            if (removeIndentation(block.toString()).equals(normalizedFind)) {
                results.add(block.toString());
            }
        }

        return results;
    }

    /**
     * 转义序列归一化替换器，处理反斜杠转义后匹配。
     */
    static List<String> escapeNormalizedReplacer(String content, String find) {
        List<String> results = new ArrayList<>();
        String unescapedFind = unescapeString(find);

        // Try direct match with unescaped find string
        if (content.contains(unescapedFind)) {
            results.add(unescapedFind);
        }

        // Also try finding escaped versions in content that match unescaped find
        String[] lines = content.split("\n", -1);
        String[] findLines = unescapedFind.split("\n", -1);

        for (int i = 0; i <= lines.length - findLines.length; i++) {
            StringBuilder block = new StringBuilder();
            for (int j = 0; j < findLines.length; j++) {
                if (j > 0) block.append("\n");
                block.append(lines[i + j]);
            }
            String unescapedBlock = unescapeString(block.toString());
            if (unescapedBlock.equals(unescapedFind)) {
                results.add(block.toString());
            }
        }

        return results;
    }

    /**
     * 边界裁剪替换器，去除查找字符串首尾空白后匹配。
     */
    static List<String> trimmedBoundaryReplacer(String content, String find) {
        List<String> results = new ArrayList<>();
        String trimmedFind = find.trim();

        if (trimmedFind.equals(find)) {
            // Already trimmed, no point in trying
            return results;
        }

        // Try to find the trimmed version
        if (content.contains(trimmedFind)) {
            results.add(trimmedFind);
        }

        // Also try finding blocks where trimmed content matches
        String[] lines = content.split("\n", -1);
        String[] findLines = find.split("\n", -1);

        for (int i = 0; i <= lines.length - findLines.length; i++) {
            StringBuilder block = new StringBuilder();
            for (int j = 0; j < findLines.length; j++) {
                if (j > 0) block.append("\n");
                block.append(lines[i + j]);
            }
            if (block.toString().trim().equals(trimmedFind)) {
                results.add(block.toString());
            }
        }

        return results;
    }

    /**
     * 上下文感知替换器，以首尾行作为锚点，检查中间内容一致性。
     */
    static List<String> contextAwareReplacer(String content, String find) {
        List<String> results = new ArrayList<>();
        List<String> findLinesList = new ArrayList<>(List.of(find.split("\n", -1)));

        if (findLinesList.size() < 3) return results;

        // Remove trailing empty line if present
        if (findLinesList.getLast().isEmpty()) {
            findLinesList.removeLast();
        }

        String[] contentLines = content.split("\n", -1);
        String firstLine = findLinesList.getFirst().trim();
        String lastLine = findLinesList.getLast().trim();

        for (int i = 0; i < contentLines.length; i++) {
            if (!contentLines[i].trim().equals(firstLine)) continue;

            for (int j = i + 2; j < contentLines.length; j++) {
                if (!contentLines[j].trim().equals(lastLine)) continue;

                int blockSize = j - i + 1;
                if (blockSize == findLinesList.size()) {
                    // Check middle content similarity
                    int matchingLines = 0;
                    int totalNonEmptyLines = 0;

                    for (int k = 1; k < blockSize - 1; k++) {
                        String blockLine = contentLines[i + k].trim();
                        String findLine = findLinesList.get(k).trim();

                        if (!blockLine.isEmpty() || !findLine.isEmpty()) {
                            totalNonEmptyLines++;
                            if (blockLine.equals(findLine)) {
                                matchingLines++;
                            }
                        }
                    }

                    if (totalNonEmptyLines == 0 || (double) matchingLines / totalNonEmptyLines >= 0.5) {
                        StringBuilder block = new StringBuilder();
                        for (int k = i; k <= j; k++) {
                            if (k > i) block.append("\n");
                            block.append(contentLines[k]);
                        }
                        results.add(block.toString());
                        return results; // only first occurrence
                    }
                }
                break;
            }
        }

        return results;
    }

    /**
     * 多次匹配替换器，返回所有精确匹配。
     */
    static List<String> multiOccurrenceReplacer(String content, String find) {
        List<String> results = new ArrayList<>();
        int startIndex = 0;
        while (true) {
            int index = content.indexOf(find, startIndex);
            if (index == -1) break;
            results.add(find);
            startIndex = index + find.length();
        }
        return results;
    }

    // ========================= 辅助方法 =============================

    /**
     * 计算两个字符串的 Levenshtein 编辑距离。
     */
    static int levenshtein(String a, String b) {
        if (a.isEmpty() || b.isEmpty()) {
            return Math.max(a.length(), b.length());
        }
        int[][] matrix = new int[a.length() + 1][b.length() + 1];
        for (int i = 0; i <= a.length(); i++) matrix[i][0] = i;
        for (int j = 0; j <= b.length(); j++) matrix[0][j] = j;

        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                matrix[i][j] = Math.min(
                    Math.min(matrix[i - 1][j] + 1, matrix[i][j - 1] + 1),
                    matrix[i - 1][j - 1] + cost
                );
            }
        }
        return matrix[a.length()][b.length()];
    }

    /**
     * 根据行号范围从内容中提取文本块。
     */
    private static String extractBlock(String content, String[] lines, int startLine, int endLine) {
        int matchStartIndex = 0;
        for (int k = 0; k < startLine; k++) {
            matchStartIndex += lines[k].length() + 1;
        }
        int matchEndIndex = matchStartIndex;
        for (int k = startLine; k <= endLine; k++) {
            matchEndIndex += lines[k].length();
            if (k < endLine) {
                matchEndIndex += 1;
            }
        }
        return content.substring(matchStartIndex, matchEndIndex);
    }

    /**
     * 将连续空白符归一化为单个空格。
     */
    private static String normalizeWhitespace(String text) {
        return text.replaceAll("\\s+", " ").trim();
    }

    /**
     * 移除文本的公共缩进。
     */
    private static String removeIndentation(String text) {
        String[] lines = text.split("\n", -1);
        int minIndent = Integer.MAX_VALUE;

        for (String line : lines) {
            if (line.trim().isEmpty()) continue;
            int indent = 0;
            while (indent < line.length() && Character.isWhitespace(line.charAt(indent))) {
                indent++;
            }
            minIndent = Math.min(minIndent, indent);
        }

        if (minIndent == Integer.MAX_VALUE || minIndent == 0) return text;

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) sb.append("\n");
            if (lines[i].trim().isEmpty()) {
                sb.append(lines[i]);
            } else {
                sb.append(lines[i].substring(minIndent));
            }
        }
        return sb.toString();
    }

    /**
     * 反转义字符串（处理 {@code \n}、{@code \t} 等转义序列）。
     */
    private static String unescapeString(String str) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < str.length(); i++) {
            if (str.charAt(i) == '\\' && i + 1 < str.length()) {
                char next = str.charAt(i + 1);
                switch (next) {
                    case 'n' -> { sb.append('\n'); i++; }
                    case 't' -> { sb.append('\t'); i++; }
                    case 'r' -> { sb.append('\r'); i++; }
                    case '\'' -> { sb.append('\''); i++; }
                    case '"' -> { sb.append('"'); i++; }
                    case '`' -> { sb.append('`'); i++; }
                    case '\\' -> { sb.append('\\'); i++; }
                    case '\n' -> { sb.append('\n'); i++; }
                    case '$' -> { sb.append('$'); i++; }
                    default -> sb.append(str.charAt(i));
                }
            } else {
                sb.append(str.charAt(i));
            }
        }
        return sb.toString();
    }

    /**
     * 检查匹配到的文本段是否与 {@code oldString} 大小不成比例。
     */
    static boolean isDisproportionateMatch(String search, String oldString) {
        int oldLines = oldString.split("\n", -1).length;
        int searchLines = search.split("\n", -1).length;
        if (searchLines >= Math.max(oldLines + 3, oldLines * 2)) return true;
        if (oldLines == 1) return false;
        return search.trim().length() > Math.max(oldString.trim().length() + 500, oldString.trim().length() * 4);
    }

    static class EditException extends RuntimeException {
        EditException(String message) {
            super(message);
        }
    }
}
