package com.aliyun.odps.agentic.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.aliyun.odps.agentic.tool.ToolContext;
import com.aliyun.odps.agentic.tool.ToolDef;
import com.aliyun.odps.agentic.tool.ToolResult;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 内置网页抓取工具，用于从 URL 获取内容。
 * 支持返回文本、Markdown 或 HTML，并内置基础的 HTML 转换能力。
 */
public class WebFetchTool implements ToolDef {

    private static final String ID = "webfetch";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String DESCRIPTION = ResourceLoader.load("tools/webfetch.txt");
    private static final int MAX_RESPONSE_SIZE = 5 * 1024 * 1024; // 5MB
    private static final int MAX_CONTENT_SIZE = 100 * 1024; // 100KB truncation limit
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();
    private static final int DEFAULT_TIMEOUT = 30;
    private static final int MAX_TIMEOUT = 120;

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
     * 返回 webfetch 工具的参数 Schema。
     *
     * <p>对应参数示例：
     * <pre>{@code
     * {
     *   "url": "https://example.com/docs",
     *   "format": "markdown",
     *   "timeout": 30
     * }
     * }</pre>
     */
    @Override
    public ObjectNode getParametersSchema() {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");

        ObjectNode properties = MAPPER.createObjectNode();

        ObjectNode url = MAPPER.createObjectNode();
        url.put("type", "string");
        url.put("description", "The URL to fetch content from");
        properties.set("url", url);

        ObjectNode format = MAPPER.createObjectNode();
        format.put("type", "string");
        format.put("description", "The format to return the content in (text, markdown, or html). Defaults to markdown.");
        format.put("default", "markdown");
        var formatEnum = MAPPER.createArrayNode();
        formatEnum.add("text");
        formatEnum.add("markdown");
        formatEnum.add("html");
        format.set("enum", formatEnum);
        properties.set("format", format);

        ObjectNode timeout = MAPPER.createObjectNode();
        timeout.put("type", "number");
        timeout.put("description", "Optional timeout in seconds (max 120)");
        properties.set("timeout", timeout);

        schema.set("properties", properties);

        var required = MAPPER.createArrayNode();
        required.add("url");
        schema.set("required", required);

        return schema;
    }

    /**
     * 从指定 URL 获取网页内容。
     *
     * <p>执行流程：
     * <ol>
     *   <li>提取 {@code url}（必填）、{@code format}（text/markdown/html，默认 markdown）和 {@code timeout}（秒，上限 120）</li>
     *   <li>HTTP URL 自动升级为 HTTPS</li>
     *   <li>使用 {@link java.net.http.HttpClient} 发送 GET 请求，响应体上限 5MB</li>
     *   <li>根据 {@code format} 参数将 HTML 转换为 Markdown 或纯文本（内置轻量转换器，无外部依赖）</li>
     *   <li>转换后内容超过 100KB 时截断</li>
     * </ol>
     */
    @Override
    public ToolResult execute(JsonNode args, ToolContext context) {
        if (!args.has("url") || args.get("url").isNull()) {
            return ToolResult.error("url is required");
        }
        String url = args.get("url").asText();
        String format = args.has("format") && !args.get("format").isNull()
                ? args.get("format").asText() : "markdown";
        int timeoutSec = args.has("timeout") ? args.get("timeout").asInt(DEFAULT_TIMEOUT) : DEFAULT_TIMEOUT;
        timeoutSec = Math.min(timeoutSec, MAX_TIMEOUT);

        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return ToolResult.error("URL must start with http:// or https://");
        }

        // 自动将 HTTP 升级为 HTTPS
        if (url.startsWith("http://")) {
            url = "https://" + url.substring("http://".length());
        }

        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(timeoutSec))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.9")
                .GET()
                .build();

            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() >= 400) {
                return ToolResult.error("HTTP " + response.statusCode() + " for " + url);
            }

            String body = response.body();
            if (body.length() > MAX_RESPONSE_SIZE) {
                return ToolResult.error("Response too large (exceeds 5MB limit)");
            }

            // 应用格式转换
            String output;
            switch (format) {
                case "html":
                    output = body;
                    break;
                case "text":
                    output = HtmlConverter.toPlainText(body);
                    break;
                case "markdown":
                default:
                    output = HtmlConverter.toMarkdown(body);
                    break;
            }

            // 内容超过 100KB 时截断
            if (output.length() > MAX_CONTENT_SIZE) {
                output = output.substring(0, MAX_CONTENT_SIZE) + "\n\n... (truncated)";
            }

            String contentType = response.headers().firstValue("content-type").orElse("");
            String title = url + " (" + contentType + ")";

            return ToolResult.of(title, output);

        } catch (Exception e) {
            return ToolResult.error("Fetch failed: " + e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // 轻量级 HTML 到 Markdown / 纯文本转换器（无外部依赖）
    // -------------------------------------------------------------------------

    static final class HtmlConverter {

        private HtmlConverter() {}

        // --- HTML 实体映射 ---------------------------------------------------

        private static final Map<String, String> ENTITIES = new HashMap<>();
        static {
            ENTITIES.put("amp", "&");
            ENTITIES.put("lt", "<");
            ENTITIES.put("gt", ">");
            ENTITIES.put("quot", "\"");
            ENTITIES.put("apos", "'");
            ENTITIES.put("nbsp", " ");
            ENTITIES.put("ndash", "–");
            ENTITIES.put("mdash", "—");
            ENTITIES.put("laquo", "«");
            ENTITIES.put("raquo", "»");
            ENTITIES.put("copy", "©");
            ENTITIES.put("reg", "®");
            ENTITIES.put("trade", "™");
            ENTITIES.put("hellip", "…");
        }

        // Precompiled patterns used by the converter
        private static final Pattern COMMENT_PATTERN =
                Pattern.compile("<!--.*?-->", Pattern.DOTALL);
        private static final Pattern STRIP_BLOCK_PATTERN =
                Pattern.compile("<(script|style|nav|footer|header)(\\s[^>]*)?>.*?</\\1>",
                        Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
        private static final Pattern HEADING_PATTERN =
                Pattern.compile("<h([1-6])(\\s[^>]*)?>(.+?)</h\\1>",
                        Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
        private static final Pattern LINK_PATTERN =
                Pattern.compile("<a\\s[^>]*href\\s*=\\s*[\"']([^\"']*)[\"'][^>]*>(.*?)</a>",
                        Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
        private static final Pattern IMG_PATTERN =
                Pattern.compile("<img\\s[^>]*?src\\s*=\\s*[\"']([^\"']*)[\"'][^>]*?>",
                        Pattern.CASE_INSENSITIVE);
        private static final Pattern PRE_PATTERN =
                Pattern.compile("<pre(\\s[^>]*)?>(.+?)</pre>",
                        Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
        private static final Pattern CODE_PATTERN =
                Pattern.compile("<code(\\s[^>]*)?>(.+?)</code>",
                        Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
        private static final Pattern BOLD_PATTERN =
                Pattern.compile("<(strong|b)(\\s[^>]*)?>(.+?)</\\1>",
                        Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
        private static final Pattern ITALIC_PATTERN =
                Pattern.compile("<(em|i)(\\s[^>]*)?>(.+?)</\\1>",
                        Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
        private static final Pattern LI_PATTERN =
                Pattern.compile("<li(\\s[^>]*)?>(.+?)</li>",
                        Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
        private static final Pattern BR_PATTERN =
                Pattern.compile("<br\\s*/?>", Pattern.CASE_INSENSITIVE);
        private static final Pattern P_OPEN_PATTERN =
                Pattern.compile("<p(\\s[^>]*)?>", Pattern.CASE_INSENSITIVE);
        private static final Pattern P_CLOSE_PATTERN =
                Pattern.compile("</p>", Pattern.CASE_INSENSITIVE);
        private static final Pattern HR_PATTERN =
                Pattern.compile("<hr\\s*/?>", Pattern.CASE_INSENSITIVE);
        private static final Pattern BLOCKQUOTE_OPEN =
                Pattern.compile("<blockquote(\\s[^>]*)?>", Pattern.CASE_INSENSITIVE);
        private static final Pattern BLOCKQUOTE_CLOSE =
                Pattern.compile("</blockquote>", Pattern.CASE_INSENSITIVE);
        private static final Pattern ALL_TAGS =
                Pattern.compile("<[^>]+>");
        private static final Pattern ENTITY_NAMED =
                Pattern.compile("&([a-zA-Z]+);");
        private static final Pattern ENTITY_DECIMAL =
                Pattern.compile("&#(\\d+);");
        private static final Pattern ENTITY_HEX =
                Pattern.compile("&#x([0-9a-fA-F]+);");
        private static final Pattern MULTI_BLANK_LINES =
                Pattern.compile("\\n{3,}");
        private static final Pattern TRAILING_SPACES =
                Pattern.compile("[ \\t]+\\n");

        // --- 公开 API --------------------------------------------------------

        /**
         * 将 HTML 转换为 Markdown。
         */
        static String toMarkdown(String html) {
            if (html == null || html.isEmpty()) {
                return "";
            }

            String s = html;

            // Remove HTML comments
            s = COMMENT_PATTERN.matcher(s).replaceAll("");

            // Strip script, style, nav, footer, header blocks
            // Apply multiple times to handle nested cases
            for (int i = 0; i < 3; i++) {
                s = STRIP_BLOCK_PATTERN.matcher(s).replaceAll("");
            }

            // <pre> blocks first (preserve inner content as-is, strip inner tags later)
            s = convertPreBlocks(s);

            // Headings: <h1>text</h1> -> # text
            s = replaceHeadings(s);

            // Links: <a href="url">text</a> -> [text](url)
            s = LINK_PATTERN.matcher(s).replaceAll("[$2]($1)");

            // Images: <img src="url" alt="text"> -> ![text](url)
            s = replaceImages(s);

            // Bold: <strong>text</strong> / <b>text</b> -> **text**
            s = BOLD_PATTERN.matcher(s).replaceAll("**$3**");

            // Italic: <em>text</em> / <i>text</i> -> *text*
            s = ITALIC_PATTERN.matcher(s).replaceAll("*$3*");

            // Inline code: <code>text</code> -> `text`
            s = CODE_PATTERN.matcher(s).replaceAll("`$2`");

            // List items: <li>text</li> -> - text
            s = LI_PATTERN.matcher(s).replaceAll("\n- $2");

            // <br> -> newline
            s = BR_PATTERN.matcher(s).replaceAll("\n");

            // <p> -> double newline
            s = P_OPEN_PATTERN.matcher(s).replaceAll("\n\n");
            s = P_CLOSE_PATTERN.matcher(s).replaceAll("\n\n");

            // <hr> -> ---
            s = HR_PATTERN.matcher(s).replaceAll("\n\n---\n\n");

            // Blockquote (simplified: just add > prefix)
            s = BLOCKQUOTE_OPEN.matcher(s).replaceAll("\n\n> ");
            s = BLOCKQUOTE_CLOSE.matcher(s).replaceAll("\n\n");

            // Strip all remaining HTML tags
            s = ALL_TAGS.matcher(s).replaceAll("");

            // Decode HTML entities
            s = decodeEntities(s);

            // Clean up whitespace
            s = TRAILING_SPACES.matcher(s).replaceAll("\n");
            s = MULTI_BLANK_LINES.matcher(s).replaceAll("\n\n");
            s = s.trim();

            return s;
        }

        /**
         * 将 HTML 转换为纯文本（去除所有标签，解码实体）。
         */
        static String toPlainText(String html) {
            if (html == null || html.isEmpty()) {
                return "";
            }

            String s = html;

            // Remove HTML comments
            s = COMMENT_PATTERN.matcher(s).replaceAll("");

            // Strip script, style, nav, footer, header blocks
            for (int i = 0; i < 3; i++) {
                s = STRIP_BLOCK_PATTERN.matcher(s).replaceAll("");
            }

            // Convert <br> to newline before stripping tags
            s = BR_PATTERN.matcher(s).replaceAll("\n");

            // Convert block-level elements to newlines
            s = P_OPEN_PATTERN.matcher(s).replaceAll("\n\n");
            s = P_CLOSE_PATTERN.matcher(s).replaceAll("");
            s = Pattern.compile("</(h[1-6]|div|section|article|li|tr|blockquote)>",
                    Pattern.CASE_INSENSITIVE).matcher(s).replaceAll("\n");

            // Strip all HTML tags
            s = ALL_TAGS.matcher(s).replaceAll("");

            // Decode HTML entities
            s = decodeEntities(s);

            // Clean up whitespace
            s = TRAILING_SPACES.matcher(s).replaceAll("\n");
            s = MULTI_BLANK_LINES.matcher(s).replaceAll("\n\n");
            s = s.trim();

            return s;
        }

        // --- 内部辅助方法 --------------------------------------------------

        private static String replaceHeadings(String s) {
            Matcher m = HEADING_PATTERN.matcher(s);
            StringBuilder sb = new StringBuilder();
            while (m.find()) {
                int level = Integer.parseInt(m.group(1));
                String prefix = "#".repeat(level);
                String content = m.group(3).trim();
                m.appendReplacement(sb, Matcher.quoteReplacement(
                        "\n\n" + prefix + " " + content + "\n\n"));
            }
            m.appendTail(sb);
            return sb.toString();
        }

        private static String replaceImages(String s) {
            Matcher m = IMG_PATTERN.matcher(s);
            StringBuilder sb = new StringBuilder();
            while (m.find()) {
                String src = m.group(1);
                // Try to extract alt attribute
                String tag = m.group(0);
                String alt = "";
                Matcher altM = Pattern.compile("alt\\s*=\\s*[\"']([^\"']*)[\"']",
                        Pattern.CASE_INSENSITIVE).matcher(tag);
                if (altM.find()) {
                    alt = altM.group(1);
                }
                m.appendReplacement(sb, Matcher.quoteReplacement(
                        "![" + alt + "](" + src + ")"));
            }
            m.appendTail(sb);
            return sb.toString();
        }

        private static String convertPreBlocks(String s) {
            Matcher m = PRE_PATTERN.matcher(s);
            StringBuilder sb = new StringBuilder();
            while (m.find()) {
                String inner = m.group(2);
                // Strip inner HTML tags (e.g., <code> inside <pre>)
                inner = ALL_TAGS.matcher(inner).replaceAll("");
                // Decode entities inside pre blocks
                inner = decodeEntities(inner);
                m.appendReplacement(sb, Matcher.quoteReplacement(
                        "\n\n```\n" + inner.trim() + "\n```\n\n"));
            }
            m.appendTail(sb);
            return sb.toString();
        }

        private static String decodeEntities(String s) {
            // Named entities: &amp; &lt; etc.
            Matcher m = ENTITY_NAMED.matcher(s);
            StringBuilder sb = new StringBuilder();
            while (m.find()) {
                String name = m.group(1).toLowerCase();
                String replacement = ENTITIES.getOrDefault(name, m.group(0));
                m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
            }
            m.appendTail(sb);
            s = sb.toString();

            // Decimal numeric entities: &#NNN;
            m = ENTITY_DECIMAL.matcher(s);
            sb = new StringBuilder();
            while (m.find()) {
                try {
                    int codePoint = Integer.parseInt(m.group(1));
                    m.appendReplacement(sb, Matcher.quoteReplacement(
                            new String(Character.toChars(codePoint))));
                } catch (Exception e) {
                    m.appendReplacement(sb, Matcher.quoteReplacement(m.group(0)));
                }
            }
            m.appendTail(sb);
            s = sb.toString();

            // Hex numeric entities: &#xHHH;
            m = ENTITY_HEX.matcher(s);
            sb = new StringBuilder();
            while (m.find()) {
                try {
                    int codePoint = Integer.parseInt(m.group(1), 16);
                    m.appendReplacement(sb, Matcher.quoteReplacement(
                            new String(Character.toChars(codePoint))));
                } catch (Exception e) {
                    m.appendReplacement(sb, Matcher.quoteReplacement(m.group(0)));
                }
            }
            m.appendTail(sb);

            return sb.toString();
        }
    }
}
