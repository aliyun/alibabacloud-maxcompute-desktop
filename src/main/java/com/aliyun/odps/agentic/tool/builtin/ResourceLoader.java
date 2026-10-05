package com.aliyun.odps.agentic.tool.builtin;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * 资源加载工具，用于读取工具描述和提示词模板等资源文件。
 * 当资源不存在或读取失败时，可返回回退内容。
 */
final class ResourceLoader {

    private ResourceLoader() {}

    static String load(String path, String fallback) {
        try (InputStream is = ResourceLoader.class.getClassLoader().getResourceAsStream(path)) {
            if (is != null) {
                return new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (Exception ignored) {}
        return fallback;
    }

    static String load(String path) {
        return load(path, "");
    }
}
