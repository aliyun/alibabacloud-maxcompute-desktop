package com.aliyun.odps.agentic.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Stream;

/**
 * 基于文件的记忆存储实现。
 * 使用 Markdown 文件保存长期记忆和每日记录，并提供简单的相关性搜索能力。
 */
public class FileMemoryStore implements MemoryStore {

    private static final Logger log = LoggerFactory.getLogger(FileMemoryStore.class);
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final Path baseDir;
    private final MemoryConfig config;

    public FileMemoryStore(Path baseDir, MemoryConfig config) {
        this.baseDir = baseDir;
        this.config = config;
    }

    @Override
    public List<MemoryEntry> search(String query, int limit) {
        if (query == null || query.isBlank()) return List.of();

        List<MemoryEntry> results = new ArrayList<>();
        String[] queryTerms = query.toLowerCase().split("\\s+");

        searchFile(baseDir.resolve(config.longTermFile()), "long-term", queryTerms, 1.5, results);

        Path dailyDir = baseDir.resolve(config.dailyNotesDir());
        if (Files.isDirectory(dailyDir)) {
            try (Stream<Path> files = Files.list(dailyDir)) {
                files.filter(p -> p.toString().endsWith(".md"))
                     .sorted(Comparator.reverseOrder())
                     .forEach(p -> searchFile(p, "daily", queryTerms, 1.0, results));
            } catch (IOException e) {
                log.warn("Failed to list daily notes: {}", e.getMessage());
            }
        }

        results.sort(Comparator.comparingDouble(MemoryEntry::score).reversed());
        return results.stream().limit(limit).toList();
    }

    @Override
    public void append(String category, String content) {
        if (content == null || content.isBlank()) return;

        try {
            Path targetFile = resolveCategoryPath(category);
            ensureParentExists(targetFile);

            String timestamp = LocalDate.now().format(DATE_FMT);
            String block = "\n## " + timestamp + "\n" + content + "\n";

            if (Files.exists(targetFile)) {
                Files.writeString(targetFile, Files.readString(targetFile) + block);
            } else {
                Files.writeString(targetFile, "# " + category + "\n" + block);
            }
            log.debug("Appended to {}: {} chars", category, content.length());
        } catch (IOException e) {
            log.error("Failed to append to memory category {}: {}", category, e.getMessage());
        }
    }

    @Override
    public void compact(String category, String summary) {
        if (summary == null || summary.isBlank()) return;

        try {
            Path targetFile = resolveCategoryPath(category);
            ensureParentExists(targetFile);

            String header = "# " + category + " (compacted)\n\n";
            String timestamp = "Compacted on " + LocalDate.now().format(DATE_FMT) + "\n\n";
            Files.writeString(targetFile, header + timestamp + summary + "\n");

            log.debug("Compacted {}: {} chars", category, summary.length());
        } catch (IOException e) {
            log.error("Failed to compact memory category {}: {}", category, e.getMessage());
        }
    }

    // ── 内部辅助方法 ─────────────────────────────────────

    private void searchFile(Path file, String category, String[] queryTerms,
                            double weightMultiplier, List<MemoryEntry> results) {
        if (!Files.exists(file) || !Files.isReadable(file)) return;

        try {
            String content = Files.readString(file);
            if (content.isBlank()) return;

            double score = computeTfIdfScore(content, queryTerms) * weightMultiplier;
            if (score > 0) {
                String snippet = extractSnippet(content, queryTerms, 300);
                results.add(new MemoryEntry(category, snippet, score));
            }
        } catch (IOException e) {
            log.warn("Failed to read memory file {}: {}", file, e.getMessage());
        }
    }

    /**
     * 计算简化版 TF-IDF 分数。
     * 在单文档场景下主要体现词频及其归一化效果。
     */
    private double computeTfIdfScore(String content, String[] queryTerms) {
        String lower = content.toLowerCase();
        int totalTerms = lower.split("\\s+").length;
        if (totalTerms == 0) return 0;

        double score = 0;
        for (String term : queryTerms) {
            if (term.isBlank()) continue;
            int count = countOccurrences(lower, term);
            if (count > 0) {
                double tf = (double) count / totalTerms;
                score += tf * (1 + Math.log(1 + count));
            }
        }
        return score;
    }

    private int countOccurrences(String text, String term) {
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(term, idx)) != -1) {
            count++;
            idx += term.length();
        }
        return count;
    }

    /**
     * 提取首个命中项附近的内容片段。
     */
    private String extractSnippet(String content, String[] queryTerms, int maxLen) {
        String lower = content.toLowerCase();
        int bestPos = -1;

        for (String term : queryTerms) {
            int pos = lower.indexOf(term);
            if (pos >= 0) {
                if (bestPos < 0 || pos < bestPos) bestPos = pos;
            }
        }

        if (bestPos < 0) {
            return content.length() <= maxLen ? content : content.substring(0, maxLen) + "...";
        }

        int start = Math.max(0, bestPos - maxLen / 3);
        int end = Math.min(content.length(), start + maxLen);
        String snippet = content.substring(start, end);
        if (start > 0) snippet = "..." + snippet;
        if (end < content.length()) snippet = snippet + "...";
        return snippet;
    }

    private Path resolveCategoryPath(String category) {
        return switch (category) {
            case "long-term" -> baseDir.resolve(config.longTermFile());
            case "daily" -> baseDir.resolve(config.dailyNotesDir())
                .resolve(LocalDate.now().format(DATE_FMT) + ".md");
            default -> baseDir.resolve(config.dailyNotesDir())
                .resolve(category + ".md");
        };
    }

    private void ensureParentExists(Path file) throws IOException {
        Path parent = file.getParent();
        if (parent != null && !Files.exists(parent)) {
            Files.createDirectories(parent);
        }
    }
}
