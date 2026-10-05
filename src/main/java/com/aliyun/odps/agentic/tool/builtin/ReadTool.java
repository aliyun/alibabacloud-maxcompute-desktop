package com.aliyun.odps.agentic.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.aliyun.odps.agentic.tool.FileReadTracker;
import com.aliyun.odps.agentic.tool.ToolContext;
import com.aliyun.odps.agentic.tool.ToolDef;
import com.aliyun.odps.agentic.tool.ToolResult;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 内置读取工具，用于读取文件或目录内容。
 * 支持文本文件、目录、图片和 PDF，并记录已读取文件用于后续写入保护。
 */
public class ReadTool implements ToolDef {

    private static final String ID = "read";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String DESCRIPTION = ResourceLoader.load("tools/read.txt");
    private static final int DEFAULT_READ_LIMIT = 2000;
    private static final int MAX_LINE_LENGTH = 2000;
    private static final int BINARY_CHECK_SIZE = 8192;

    private static final Set<String> IMAGE_EXTENSIONS = Set.of(
            "png", "jpg", "jpeg", "gif", "bmp", "webp", "svg"
    );

    private static final Map<String, String> IMAGE_MIME_TYPES = Map.of(
            "png", "image/png",
            "jpg", "image/jpeg",
            "jpeg", "image/jpeg",
            "gif", "image/gif",
            "bmp", "image/bmp",
            "webp", "image/webp",
            "svg", "image/svg+xml"
    );

    @Override
    public String getId() { return ID; }

    // 只读工具：可与其他只读工具并行执行（0.4.0）。
    @Override
    public boolean isReadOnly() { return true; }

    @Override
    public String getDescription() {
        return DESCRIPTION;
    }

    /**
     * 返回 read 工具的参数 Schema。
     *
     * <p>对应参数示例：
     * <pre>{@code
     * {
     *   "filePath": "/abs/path/README.md",
     *   "offset": 1,
     *   "limit": 200
     * }
     * }</pre>
     */
    @Override
    public ObjectNode getParametersSchema() {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");

        ObjectNode properties = MAPPER.createObjectNode();

        ObjectNode filePath = MAPPER.createObjectNode();
        filePath.put("type", "string");
        filePath.put("description", "The absolute path to the file or directory to read");
        properties.set("filePath", filePath);

        ObjectNode offset = MAPPER.createObjectNode();
        offset.put("type", "number");
        offset.put("description", "The line number to start reading from (1-indexed)");
        properties.set("offset", offset);

        ObjectNode limit = MAPPER.createObjectNode();
        limit.put("type", "number");
        limit.put("description", "The maximum number of lines to read (defaults to 2000)");
        properties.set("limit", limit);

        schema.set("properties", properties);

        var required = MAPPER.createArrayNode();
        required.add("filePath");
        schema.set("required", required);

        return schema;
    }

    /**
     * 执行文件或目录读取。
     *
     * <p>执行流程：
     * <ol>
     *   <li>根据路径类型分发到不同读取策略：
     *       目录 → 列出条目；图片 → Base64 编码；PDF → 尝试 pdftotext 提取文本；
     *       普通文件 → 按行号范围读取文本</li>
     *   <li>对文本文件进行二进制检测（扫描前 8KB 有无空字节），拒绝读取二进制文件</li>
     *   <li>支持 {@code offset}（起始行号，1-indexed）和 {@code limit}（最大行数）分页</li>
     *   <li>单行超过 2000 字符时自动截断</li>
     *   <li>成功读取后，通过 {@link FileReadTracker} 记录文件路径，供 EditTool/WriteTool 做先读后写检查</li>
     * </ol>
     */
    @Override
    public ToolResult execute(JsonNode args, ToolContext context) {
        String filePath = ToolDef.extractString(args, "filePath", "file_path");
        if (filePath == null || filePath.isEmpty()) {
            return ToolResult.error("filePath is required");
        }

        int offset = args.has("offset") ? args.get("offset").asInt(1) : 1;
        int limit = args.has("limit") ? args.get("limit").asInt(DEFAULT_READ_LIMIT) : DEFAULT_READ_LIMIT;

        try {
            Path path = Path.of(filePath);
            if (!Files.exists(path)) {
                return ToolResult.error("File not found: " + filePath);
            }

            if (Files.isDirectory(path)) {
                return readDirectory(path, filePath, offset, limit);
            }

            String extension = getFileExtension(filePath).toLowerCase(Locale.ROOT);

            // Handle image files
            if (IMAGE_EXTENSIONS.contains(extension)) {
                trackRead(context, path);
                return readImage(path, filePath, extension);
            }

            // Handle PDF files
            if ("pdf".equals(extension)) {
                trackRead(context, path);
                return readPdf(path, filePath);
            }

            // Check for binary content before attempting text read
            if (isBinaryFile(path)) {
                return ToolResult.error("Binary file detected. Cannot display as text: " + filePath);
            }

            ToolResult result = readFile(path, filePath, offset, limit);
            trackRead(context, path);
            return result;

        } catch (IOException e) {
            return ToolResult.error("Failed to read: " + e.getMessage());
        }
    }

    private void trackRead(ToolContext context, Path path) {
        FileReadTracker tracker = FileReadTracker.from(context);
        if (tracker != null) {
            tracker.markRead(path.toAbsolutePath().toString());
        }
    }

    private ToolResult readDirectory(Path path, String filePath, int offset, int limit) throws IOException {
        List<String> entries = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(path)) {
            for (Path entry : stream) {
                String name = entry.getFileName().toString();
                if (Files.isDirectory(entry)) {
                    name += "/";
                }
                entries.add(name);
            }
        }
        Collections.sort(entries);

        int start = Math.max(0, offset - 1);
        int end = Math.min(start + limit, entries.size());
        List<String> sliced = entries.subList(start, end);
        boolean truncated = end < entries.size();

        StringBuilder sb = new StringBuilder();
        sb.append("<path>").append(filePath).append("</path>\n");
        sb.append("<type>directory</type>\n");
        sb.append("<entries>\n");
        sb.append(String.join("\n", sliced));
        if (truncated) {
            sb.append("\n\n(Showing ").append(sliced.size()).append(" of ").append(entries.size())
              .append(" entries. Use 'offset' parameter to read beyond entry ")
              .append(offset + sliced.size()).append(")");
        } else {
            sb.append("\n\n(").append(entries.size()).append(" entries)");
        }
        sb.append("\n</entries>");

        return ToolResult.of(filePath, sb.toString());
    }

    private ToolResult readFile(Path path, String filePath, int offset, int limit) throws IOException {
        List<String> allLines = Files.readAllLines(path);
        int totalLines = allLines.size();

        if (totalLines == 0 && offset == 1) {
            // Empty file
            StringBuilder sb = new StringBuilder();
            sb.append("<path>").append(filePath).append("</path>\n");
            sb.append("<type>file</type>\n");
            sb.append("<content>\n");
            sb.append("\n\n(End of file - total 0 lines)");
            sb.append("\n</content>");
            return ToolResult.of(filePath, sb.toString());
        }

        if (offset > totalLines && !(totalLines == 0 && offset == 1)) {
            return ToolResult.error("Offset " + offset + " is out of range for this file (" + totalLines + " lines)");
        }

        int start = offset - 1; // Convert to 0-based
        int end = Math.min(start + limit, totalLines);

        StringBuilder sb = new StringBuilder();
        sb.append("<path>").append(filePath).append("</path>\n");
        sb.append("<type>file</type>\n");
        sb.append("<content>\n");

        for (int i = start; i < end; i++) {
            String line = allLines.get(i);
            if (line.length() > MAX_LINE_LENGTH) {
                line = line.substring(0, MAX_LINE_LENGTH) + "... (line truncated to " + MAX_LINE_LENGTH + " chars)";
            }
            sb.append(i + 1).append(": ").append(line);
            if (i < end - 1) {
                sb.append("\n");
            }
        }

        int last = end;
        int next = last + 1;
        boolean more = end < totalLines;

        if (more) {
            sb.append("\n\n(Showing lines ").append(offset).append("-").append(last)
              .append(" of ").append(totalLines).append(". Use offset=").append(next).append(" to continue.)");
        } else {
            sb.append("\n\n(End of file - total ").append(totalLines).append(" lines)");
        }
        sb.append("\n</content>");

        return ToolResult.of(filePath, sb.toString());
    }

    // ---- 图片支持 ----

    private ToolResult readImage(Path path, String filePath, String extension) throws IOException {
        byte[] bytes = Files.readAllBytes(path);
        String base64 = Base64.getEncoder().encodeToString(bytes);
        String mimeType = IMAGE_MIME_TYPES.getOrDefault(extension, "application/octet-stream");

        StringBuilder sb = new StringBuilder();
        sb.append("<image>\n");
        sb.append("<path>").append(filePath).append("</path>\n");
        sb.append("<type>").append(mimeType).append("</type>\n");
        sb.append("<data>").append(base64).append("</data>\n");
        sb.append("</image>");

        return ToolResult.of(filePath, sb.toString());
    }

    // ---- PDF 支持 ----

    private ToolResult readPdf(Path path, String filePath) throws IOException {
        long fileSize = Files.size(path);

        // Try pdftotext first (commonly available on macOS/Linux)
        String extractedText = tryPdfToText(path);
        if (extractedText != null) {
            StringBuilder sb = new StringBuilder();
            sb.append("<path>").append(filePath).append("</path>\n");
            sb.append("<type>pdf</type>\n");
            sb.append("<content>\n");
            sb.append(extractedText);
            if (fileSize > 1_000_000) {
                sb.append("\n\n(Large PDF file: ").append(fileSize / 1024).append(" KB. ")
                  .append("Text extracted via pdftotext. Use offset/limit to paginate if needed.)");
            }
            sb.append("\n</content>");
            return ToolResult.of(filePath, sb.toString());
        }

        // Fallback: return as base64
        byte[] bytes = Files.readAllBytes(path);
        String base64 = Base64.getEncoder().encodeToString(bytes);

        StringBuilder sb = new StringBuilder();
        sb.append("<pdf>\n");
        sb.append("<path>").append(filePath).append("</path>\n");
        sb.append("<type>application/pdf</type>\n");
        sb.append("<note>pdftotext not available; returning raw base64 data</note>\n");
        sb.append("<data>").append(base64).append("</data>\n");
        sb.append("</pdf>");

        return ToolResult.of(filePath, sb.toString());
    }

    /**
     * 尝试使用 {@code pdftotext} 命令行工具提取 PDF 文本。
     * 不可用或失败时返回 {@code null}。
     */
    private String tryPdfToText(Path path) {
        try {
            ProcessBuilder pb = new ProcessBuilder("pdftotext", "-layout", path.toString(), "-");
            pb.redirectErrorStream(true);
            Process process = pb.start();

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append('\n');
                }
            }

            boolean finished = process.waitFor(30, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return null;
            }

            if (process.exitValue() != 0) {
                return null;
            }

            return output.toString();
        } catch (IOException | InterruptedException e) {
            // pdftotext not installed or failed
            return null;
        }
    }

    // ---- 二进制检测 ----

    /**
     * 扫描文件前 8KB 中是否包含空字节，以判断是否为二进制文件。
     */
    private boolean isBinaryFile(Path path) throws IOException {
        try (InputStream is = Files.newInputStream(path)) {
            byte[] buffer = new byte[BINARY_CHECK_SIZE];
            int bytesRead = is.read(buffer);
            if (bytesRead <= 0) {
                return false; // empty file is not binary
            }
            for (int i = 0; i < bytesRead; i++) {
                if (buffer[i] == 0) {
                    return true;
                }
            }
            return false;
        }
    }

    // ---- 工具方法 ----

    private String getFileExtension(String filePath) {
        int lastDot = filePath.lastIndexOf('.');
        if (lastDot < 0 || lastDot == filePath.length() - 1) {
            return "";
        }
        // Make sure the dot is after the last separator
        int lastSep = Math.max(filePath.lastIndexOf('/'), filePath.lastIndexOf('\\'));
        if (lastDot < lastSep) {
            return "";
        }
        return filePath.substring(lastDot + 1);
    }
}
