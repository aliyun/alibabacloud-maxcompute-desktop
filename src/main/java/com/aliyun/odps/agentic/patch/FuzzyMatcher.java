package com.aliyun.odps.agentic.patch;

import java.util.List;

/**
 * 补丁应用的模糊匹配器。
 * 用于在旧行无法精确匹配时，通过多级策略定位最合适的替换位置。
 */
public class FuzzyMatcher {

    private static final double LEVENSHTEIN_THRESHOLD = 0.4;
    private static final int MAX_LEVENSHTEIN_LENGTH = 200;

    /**
     * 在文件内容中查找 {@code oldLines} 的最佳匹配位置。
     * 按精确匹配、去首尾空白、缩进归一化和 Levenshtein 匹配的顺序尝试。
     *
     * @param fileContent 文件内容行列表
     * @param oldLines    待查找的旧行
     * @param startLine   搜索起始提示行号，从 0 开始，可为空
     * @return 匹配起始下标，从 0 开始；未找到时返回 -1
     */
    public int findMatch(List<String> fileContent, List<String> oldLines, Integer startLine) {
        if (oldLines.isEmpty()) return 0;
        int searchStart = startLine != null ? Math.max(0, startLine) : 0;
        int searchEnd = fileContent.size() - oldLines.size() + 1;

        int exact = findExact(fileContent, oldLines, searchStart, searchEnd);
        if (exact >= 0) return exact;

        int trimmed = findTrimmed(fileContent, oldLines, searchStart, searchEnd);
        if (trimmed >= 0) return trimmed;

        int normalized = findNormalized(fileContent, oldLines, searchStart, searchEnd);
        if (normalized >= 0) return normalized;

        int fuzzy = findLevenshtein(fileContent, oldLines, searchStart, searchEnd);
        return fuzzy;
    }

    // ── 第 1 层：精确匹配 ──

    private int findExact(List<String> file, List<String> old, int start, int end) {
        for (int i = start; i < end; i++) {
            if (matchesExact(file, i, old)) return i;
        }
        return -1;
    }

    private boolean matchesExact(List<String> file, int offset, List<String> old) {
        for (int j = 0; j < old.size(); j++) {
            if (!file.get(offset + j).equals(old.get(j))) return false;
        }
        return true;
    }

    // ── 第 2 层：去首尾空白匹配 ──

    private int findTrimmed(List<String> file, List<String> old, int start, int end) {
        for (int i = start; i < end; i++) {
            if (matchesTrimmed(file, i, old)) return i;
        }
        return -1;
    }

    private boolean matchesTrimmed(List<String> file, int offset, List<String> old) {
        for (int j = 0; j < old.size(); j++) {
            if (!file.get(offset + j).trim().equals(old.get(j).trim())) return false;
        }
        return true;
    }

    // ── 第 3 层：缩进归一化匹配 ──

    private int findNormalized(List<String> file, List<String> old, int start, int end) {
        for (int i = start; i < end; i++) {
            if (matchesNormalized(file, i, old)) return i;
        }
        return -1;
    }

    private boolean matchesNormalized(List<String> file, int offset, List<String> old) {
        for (int j = 0; j < old.size(); j++) {
            String fLine = normalizeIndent(file.get(offset + j));
            String oLine = normalizeIndent(old.get(j));
            if (!fLine.equals(oLine)) return false;
        }
        return true;
    }

    /** 去除公共前导空白后再进行 trim。 */
    private String normalizeIndent(String line) {
        return line.replace("\t", "    ").trim();
    }

    // ── 第 4 层：Levenshtein 模糊匹配 ──

    private int findLevenshtein(List<String> file, List<String> old, int start, int end) {
        double bestScore = Double.MAX_VALUE;
        int bestIdx = -1;

        for (int i = start; i < end; i++) {
            double score = computeLineDistance(file, i, old);
            if (score < bestScore) {
                bestScore = score;
                bestIdx = i;
            }
        }

        double avgDist = bestScore / old.size();
        return avgDist <= LEVENSHTEIN_THRESHOLD ? bestIdx : -1;
    }

    private double computeLineDistance(List<String> file, int offset, List<String> old) {
        double total = 0;
        for (int j = 0; j < old.size(); j++) {
            String fLine = file.get(offset + j).trim();
            String oLine = old.get(j).trim();
            if (fLine.length() > MAX_LEVENSHTEIN_LENGTH || oLine.length() > MAX_LEVENSHTEIN_LENGTH) {
                total += 1.0;
            } else {
                int maxLen = Math.max(fLine.length(), oLine.length());
                if (maxLen == 0) continue;
                total += (double) levenshteinDistance(fLine, oLine) / maxLen;
            }
        }
        return total;
    }

    /**
     * 计算两个字符串的 Levenshtein 编辑距离。
     *
     * @param a 第一个字符串
     * @param b 第二个字符串
     * @return 编辑距离
     */
    public int levenshteinDistance(String a, String b) {
        int m = a.length();
        int n = b.length();
        int[] prev = new int[n + 1];
        int[] curr = new int[n + 1];

        for (int j = 0; j <= n; j++) prev[j] = j;

        for (int i = 1; i <= m; i++) {
            curr[0] = i;
            for (int j = 1; j <= n; j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                curr[j] = Math.min(
                    Math.min(prev[j] + 1, curr[j - 1] + 1),
                    prev[j - 1] + cost
                );
            }
            int[] tmp = prev; prev = curr; curr = tmp;
        }
        return prev[n];
    }

    /**
     * 判断两个字符串是否足够相似。
     *
     * @param a 第一个字符串
     * @param b 第二个字符串
     * @return 是否满足相似度阈值
     */
    public boolean isSimilar(String a, String b) {
        if (a.length() > MAX_LEVENSHTEIN_LENGTH || b.length() > MAX_LEVENSHTEIN_LENGTH) {
            return false;
        }
        int maxLen = Math.max(a.length(), b.length());
        if (maxLen == 0) return true;
        double distance = (double) levenshteinDistance(a, b) / maxLen;
        return distance <= LEVENSHTEIN_THRESHOLD;
    }
}
