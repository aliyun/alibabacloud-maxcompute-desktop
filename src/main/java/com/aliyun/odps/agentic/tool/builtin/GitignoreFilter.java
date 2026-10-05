package com.aliyun.odps.agentic.tool.builtin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 处理 {@code .gitignore} 规则的共享工具，并提供按修改时间排序的能力。
 * 主要供 {@link GlobTool} 和 {@link GrepTool} 使用。
 */
final class GitignoreFilter {

    /** 始终跳过的目录列表，不受 .gitignore 内容影响。 */
    private static final List<String> ALWAYS_SKIP = List.of(
            ".git", "node_modules", "target", "build"
    );

    private final List<IgnoreRule> rules;

    private GitignoreFilter(List<IgnoreRule> rules) {
        this.rules = rules;
    }

    // ------------------------------------------------------------------
    // 工厂方法
    // ------------------------------------------------------------------

    /**
     * 读取 {@code root} 下的 {@code .gitignore} 文件来构建过滤器。
     * 无论 .gitignore 内容如何，内置跳过列表始终生效。
     */
    static GitignoreFilter forRoot(Path root) {
        Path gitignore = root.resolve(".gitignore");
        List<IgnoreRule> rules = new ArrayList<>();
        if (Files.isRegularFile(gitignore)) {
            try {
                List<String> lines = Files.readAllLines(gitignore);
                for (String raw : lines) {
                    String line = raw.trim();
                    if (line.isEmpty() || line.startsWith("#")) {
                        continue;
                    }
                    boolean negated = false;
                    if (line.startsWith("!")) {
                        negated = true;
                        line = line.substring(1);
                    }
                    boolean dirOnly = line.endsWith("/");
                    if (dirOnly) {
                        line = line.substring(0, line.length() - 1);
                    }
                    Pattern regex = globToRegex(line);
                    rules.add(new IgnoreRule(regex, negated, dirOnly));
                }
            } catch (IOException ignored) {
                // If we can't read .gitignore, proceed with no rules.
            }
        }
        return new GitignoreFilter(Collections.unmodifiableList(rules));
    }

    // ------------------------------------------------------------------
    // 公开 API
    // ------------------------------------------------------------------

    /**
     * 判断给定路径是否应被跳过（即不包含在搜索结果中）。
     *
     * @param path 待判定的路径
     * @param root 遍历的根目录（用于计算相对路径）
     */
    boolean shouldSkip(Path path, Path root) {
        // Check every component against the always-skip list.
        Path rel = root.relativize(path);
        for (int i = 0; i < rel.getNameCount(); i++) {
            String component = rel.getName(i).toString();
            if (ALWAYS_SKIP.contains(component)) {
                return true;
            }
        }

        // Evaluate .gitignore rules in order; last matching rule wins.
        String relStr = rel.toString().replace('\\', '/');
        boolean isDir = Files.isDirectory(path);

        boolean ignored = false;
        for (IgnoreRule rule : rules) {
            if (rule.dirOnly && !isDir) {
                continue;
            }
            if (rule.pattern.matcher(relStr).matches()) {
                ignored = !rule.negated;
            }
        }
        return ignored;
    }

    // ------------------------------------------------------------------
    // 排序辅助
    // ------------------------------------------------------------------

    /**
     * 按最后修改时间降序排序路径列表。
     */
    static void sortByModifiedTimeDesc(List<Path> paths) {
        paths.sort(Comparator.<Path, Long>comparing(p -> {
            try {
                return Files.getLastModifiedTime(p).toMillis();
            } catch (IOException e) {
                return 0L;
            }
        }).reversed());
    }

    // ------------------------------------------------------------------
    // Glob 转正则
    // ------------------------------------------------------------------

    /**
     * 将 .gitignore 的 glob 模式转换为 Java {@link Pattern}。
     * <ul>
     *   <li>{@code *}  匹配除 {@code /} 外的任意字符</li>
     *   <li>{@code **} 匹配任意层级目录（含零层）</li>
     *   <li>{@code ?}  匹配除 {@code /} 外的单个字符</li>
     *   <li>若模式不含 {@code /}，可匹配文件名或任意路径组件</li>
     * </ul>
     */
    private static Pattern globToRegex(String glob) {
        // Determine whether the pattern should be anchored to the root.
        // If the pattern contains a '/' (other than trailing, already stripped),
        // it is anchored; otherwise it can match in any directory.
        boolean anchored = glob.contains("/");

        StringBuilder sb = new StringBuilder();

        if (!anchored) {
            // Match at any depth: "(.*/)?"
            sb.append("(.*/)?");
        } else if (glob.startsWith("/")) {
            glob = glob.substring(1); // strip leading /
        }

        int i = 0;
        int len = glob.length();
        while (i < len) {
            char c = glob.charAt(i);
            if (c == '*') {
                if (i + 1 < len && glob.charAt(i + 1) == '*') {
                    // "**"
                    if (i + 2 < len && glob.charAt(i + 2) == '/') {
                        sb.append("(.*/)?");
                        i += 3;
                    } else {
                        sb.append(".*");
                        i += 2;
                    }
                } else {
                    sb.append("[^/]*");
                    i++;
                }
            } else if (c == '?') {
                sb.append("[^/]");
                i++;
            } else if (c == '[') {
                // pass through character class
                sb.append('[');
                i++;
                while (i < len && glob.charAt(i) != ']') {
                    sb.append(glob.charAt(i));
                    i++;
                }
                if (i < len) {
                    sb.append(']');
                    i++;
                }
            } else if (".(){}+|^$\\".indexOf(c) >= 0) {
                sb.append('\\').append(c);
                i++;
            } else {
                sb.append(c);
                i++;
            }
        }

        return Pattern.compile(sb.toString());
    }

    // ------------------------------------------------------------------
    // 内部记录
    // ------------------------------------------------------------------

    private record IgnoreRule(Pattern pattern, boolean negated, boolean dirOnly) {}
}
