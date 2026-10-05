package com.aliyun.odps.agentic.skill;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 远程技能仓库发现器。
 * 负责从远程仓库发现技能、下载技能内容并写入本地缓存。
 */
public class SkillDiscovery {

    private static final Logger log = LoggerFactory.getLogger(SkillDiscovery.class);

    private final HttpClient httpClient;
    private final Path cacheDir;

    public SkillDiscovery(Path cacheDir) {
        this.cacheDir = cacheDir;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    }

    /**
     * 从远程仓库发现技能。
     * 仅返回包含 {@code SKILL.md} 的技能条目。
     *
     * @param repositoryUrl 技能仓库基础 URL
     * @return 发现到的技能列表
     */
    public List<SkillInfo> discoverFromRepository(String repositoryUrl) {
        if (repositoryUrl == null || repositoryUrl.isBlank()) {
            return Collections.emptyList();
        }
        String base = repositoryUrl.endsWith("/") ? repositoryUrl : repositoryUrl + "/";

        // 1. 获取 index.json
        String indexJson = fetchUrl(base + "index.json");
        if (indexJson == null) {
            log.warn("Failed to fetch skill index from: {}", base);
            return Collections.emptyList();
        }

        // 2. 解析索引，避免引入 Jackson 依赖
        List<IndexEntry> entries = parseIndex(indexJson);
        if (entries.isEmpty()) {
            log.info("No skills found in index at: {}", base);
            return Collections.emptyList();
        }

        // 3. 下载技能并构建 SkillInfo
        List<SkillInfo> results = new ArrayList<>();
        for (IndexEntry entry : entries) {
            if (!entry.files.contains("SKILL.md")) {
                log.warn("Skill '{}' missing SKILL.md, skipping", entry.name);
                continue;
            }

            String skillMd = fetchUrl(base + entry.name + "/SKILL.md");
            if (skillMd != null) {
                results.add(new SkillInfo(
                    entry.name,
                    extractDescription(skillMd),
                    base + entry.name,
                    skillMd
                ));

                cacheSkill(entry.name, skillMd);
            }
        }

        log.info("Discovered {} skill(s) from {}", results.size(), repositoryUrl);
        return results;
    }

    // ── 内部辅助方法 ─────────────────────────────────────

    private String fetchUrl(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Accept", "application/json, text/plain, text/markdown")
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return response.body();
            }
            log.warn("HTTP {} fetching {}", response.statusCode(), url);
            return null;
        } catch (IllegalArgumentException | IOException | InterruptedException e) {
            log.warn("Failed to fetch {}: {}", url, e.getMessage());
            return null;
        }
    }

    /**
     * 解析最小化 JSON 索引，不依赖外部库。
     * 处理格式为 {@code {"skills":[{"name":"...","files":["SKILL.md",...]}]}} 的结构。
     */
    private List<IndexEntry> parseIndex(String json) {
        List<IndexEntry> entries = new ArrayList<>();
        try {
            int skillsIdx = json.indexOf("\"skills\"");
            if (skillsIdx < 0) return entries;
            int arrayStart = json.indexOf('[', skillsIdx);
            int arrayEnd = json.lastIndexOf(']');
            if (arrayStart < 0 || arrayEnd < 0) return entries;

            String arrayContent = json.substring(arrayStart + 1, arrayEnd);

            int depth = 0;
            int objStart = -1;
            for (int i = 0; i < arrayContent.length(); i++) {
                char c = arrayContent.charAt(i);
                if (c == '{') {
                    if (depth == 0) objStart = i;
                    depth++;
                } else if (c == '}') {
                    depth--;
                    if (depth == 0 && objStart >= 0) {
                        String obj = arrayContent.substring(objStart, i + 1);
                        IndexEntry entry = parseEntry(obj);
                        if (entry != null) entries.add(entry);
                        objStart = -1;
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to parse skill index: {}", e.getMessage());
        }
        return entries;
    }

    private IndexEntry parseEntry(String obj) {
        String name = extractJsonString(obj, "name");
        if (name == null) return null;

        List<String> files = new ArrayList<>();
        int filesIdx = obj.indexOf("\"files\"");
        if (filesIdx >= 0) {
            int arrStart = obj.indexOf('[', filesIdx);
            int arrEnd = obj.indexOf(']', arrStart);
            if (arrStart >= 0 && arrEnd >= 0) {
                String arr = obj.substring(arrStart + 1, arrEnd);
                for (String part : arr.split(",")) {
                    String f = part.trim().replaceAll("\"", "");
                    if (!f.isEmpty()) files.add(f);
                }
            }
        }

        return new IndexEntry(name, files);
    }

    private String extractJsonString(String json, String key) {
        String searchKey = "\"" + key + "\"";
        int idx = json.indexOf(searchKey);
        if (idx < 0) return null;
        int colon = json.indexOf(':', idx + searchKey.length());
        if (colon < 0) return null;
        int start = json.indexOf('"', colon + 1);
        if (start < 0) return null;
        int end = json.indexOf('"', start + 1);
        if (end < 0) return null;
        return json.substring(start + 1, end);
    }

    /** 从 {@code SKILL.md} 中提取第一条非标题、非空行作为描述。 */
    private String extractDescription(String skillMd) {
        String[] lines = skillMd.split("\n");
        for (String line : lines) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                return trimmed.length() <= 200 ? trimmed : trimmed.substring(0, 197) + "...";
            }
        }
        return null;
    }

    private void cacheSkill(String name, String content) {
        try {
            Path skillDir = cacheDir.resolve(name);
            Files.createDirectories(skillDir);
            Files.writeString(skillDir.resolve("SKILL.md"), content);
        } catch (IOException e) {
            log.debug("Failed to cache skill {}: {}", name, e.getMessage());
        }
    }

    // ── 内部模型 ───────────────────────────────────────

    private record IndexEntry(String name, List<String> files) {}
}
