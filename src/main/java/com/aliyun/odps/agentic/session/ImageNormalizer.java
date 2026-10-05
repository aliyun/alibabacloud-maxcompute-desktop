package com.aliyun.odps.agentic.session;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Set;

/**
 * 图片归一化工具。
 *
 * <p>负责判断提供者支持的图片 MIME 类型，并在需要时将本地文件转换为 Data URI。
 */
public final class ImageNormalizer {

    private ImageNormalizer() {}

    // ── 各提供者支持的 MIME 类型 ──

    private static final Set<String> UNIVERSAL_MEDIA_TYPES = Set.of(
        "image/png", "image/jpeg", "image/gif", "image/webp"
    );

    private static final Set<String> ANTHROPIC_MEDIA_TYPES = UNIVERSAL_MEDIA_TYPES;
    private static final Set<String> OPENAI_MEDIA_TYPES = UNIVERSAL_MEDIA_TYPES;
    private static final Set<String> GOOGLE_MEDIA_TYPES = Set.of(
        "image/png", "image/jpeg", "image/webp", "image/heic", "image/heif"
    );

    /**
     * 判断 MIME 类型是否为图片媒体类型。
     *
     * @param mime MIME 类型
     * @return 是图片则返回 {@code true}
     */
    public static boolean isMedia(String mime) {
        return mime != null && mime.startsWith("image/");
    }

    /**
     * 判断提供者是否支持在工具结果中内联该图片类型。
     *
     * @param providerId 提供者 ID
     * @param mime MIME 类型
     * @return 支持返回 {@code true}
     */
    public static boolean supportsMediaInToolResult(String providerId, String mime) {
        if (providerId == null) return false;
        return switch (providerId.toLowerCase()) {
            case "anthropic" -> ANTHROPIC_MEDIA_TYPES.contains(mime);
            case "google", "gemini", "google-vertex" -> GOOGLE_MEDIA_TYPES.contains(mime);
            default -> OPENAI_MEDIA_TYPES.contains(mime);
        };
    }

    /**
     * 判断给定提供者是否支持该图片 MIME 类型。
     *
     * @param providerId 提供者 ID
     * @param mime MIME 类型
     * @return 支持返回 {@code true}
     */
    public static boolean isSupportedByProvider(String providerId, String mime) {
        if (!isMedia(mime)) return false;
        return switch (providerId.toLowerCase()) {
            case "anthropic" -> ANTHROPIC_MEDIA_TYPES.contains(mime);
            case "google", "gemini", "google-vertex" -> GOOGLE_MEDIA_TYPES.contains(mime);
            default -> UNIVERSAL_MEDIA_TYPES.contains(mime);
        };
    }

    /**
     * 将本地图片文件转换为 Base64 Data URI。
     *
     * @param imagePath 图片路径
     * @return Data URI 字符串
     * @throws IOException 读取文件失败时抛出
     */
    public static String toDataUri(Path imagePath) throws IOException {
        String mime = detectMime(imagePath);
        byte[] bytes = Files.readAllBytes(imagePath);
        String base64 = Base64.getEncoder().encodeToString(bytes);
        return "data:" + mime + ";base64," + base64;
    }

    /**
     * 解析 Data URI 为结构化组件。
     *
     * @param dataUri Data URI 字符串
     * @return 解析结果；格式非法时返回 {@code null}
     */
    public static DataUriComponents parseDataUri(String dataUri) {
        if (dataUri == null || !dataUri.startsWith("data:")) return null;
        int semiIdx = dataUri.indexOf(';');
        int commaIdx = dataUri.indexOf(',');
        if (semiIdx < 0 || commaIdx < 0) return null;
        String mime = dataUri.substring(5, semiIdx);
        String encoding = dataUri.substring(semiIdx + 1, commaIdx);
        String data = dataUri.substring(commaIdx + 1);
        return new DataUriComponents(mime, encoding, data);
    }

    /**
     * 表示解析后的 Data URI 组件。
     *
     * @param mime MIME 类型
     * @param encoding 编码方式
     * @param data 原始数据部分
     */
    public record DataUriComponents(String mime, String encoding, String data) {
        /**
         * 将数据部分解码为字节数组。
         *
         * @return 解码后的字节内容
         */
        public byte[] decode() {
            if ("base64".equals(encoding)) {
                return Base64.getDecoder().decode(data);
            }
            return data.getBytes();
        }
    }

    /**
     * 根据文件扩展名推断 MIME 类型。
     */
    private static String detectMime(Path path) {
        String name = path.getFileName().toString().toLowerCase();
        if (name.endsWith(".png")) return "image/png";
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) return "image/jpeg";
        if (name.endsWith(".gif")) return "image/gif";
        if (name.endsWith(".webp")) return "image/webp";
        if (name.endsWith(".heic")) return "image/heic";
        if (name.endsWith(".heif")) return "image/heif";
        return "application/octet-stream";
    }
}
