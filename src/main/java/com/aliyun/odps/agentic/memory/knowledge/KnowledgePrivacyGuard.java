package com.aliyun.odps.agentic.memory.knowledge;


import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 知识库隐私守卫（M0 门禁 #8）——本地文件内容将上传云端 embedding，逐文件把关。
 *
 * <p>层级：① LocalPathGuard 敏感路径（realPath 解析符号链接后）② 凭据/私钥扩展名与文件名
 * denylist ③ 常见构建/依赖目录默认忽略 ④ KB 根目录 .knowledgeignore（gitignore 语法子集：
 * 每行一个 glob，# 注释，! 取反不支持——v1 保持简单）。</p>
 */
public class KnowledgePrivacyGuard {
    private final java.util.function.Predicate<String> sensitivePath;
    public KnowledgePrivacyGuard(java.util.function.Predicate<String> sensitivePath) {
        this.sensitivePath=java.util.Objects.requireNonNull(sensitivePath);
    }
    public boolean isSensitivePath(String path) { return sensitivePath.test(path); }


    /** Office 临时锁文件前缀(用户开着文档时生成,内容必坏,入库必 extract_failed)。 */
    private static final String OFFICE_LOCK_PREFIX = "~$";
    /** OS 垃圾文件(与内容无关,永远跳过)。 */
    private static final Set<String> OS_JUNK_NAMES = Set.of(
            ".ds_store", "thumbs.db", "desktop.ini", ".spotlight-v100", ".trashes");

    /** 凭据/私钥类文件名与扩展名——永不入库（内容上传风险面）。 */
    private static final Set<String> DENIED_EXTS = Set.of(
            "pem", "key", "p12", "pfx", "jks", "keystore", "crt", "cer", "der", "kdbx", "asc", "gpg");
    private static final Set<String> DENIED_NAMES = Set.of(
            ".env", ".env.local", ".env.production", ".env.development", ".npmrc", ".pypirc",
            "id_rsa", "id_dsa", "id_ecdsa", "id_ed25519", "credentials", "secrets.json");

    /** 默认忽略的目录（构建产物/依赖/版本控制）。 */
    private static final Set<String> IGNORED_DIRS = Set.of(
            ".git", ".svn", ".idea", ".vscode", "node_modules", "target", "build", "dist",
            "__pycache__", ".venv", "venv", ".gradle", ".m2", "out", "bin", "obj");

    public record Verdict(boolean allowed, String reason) {
        static Verdict ok() { return new Verdict(true, null); }
        static Verdict denied(String reason) { return new Verdict(false, reason); }
    }

    /** 根目录规范化（realPath 解析符号链接——@TempDir/~/Documents 等路径会经 /var→/private 这类
        链接,不解析则 relativize 出 ../../ 前缀,前缀规则全灭）。 */
    private static Path canonicalRoot(Path kbRoot) {
        try { return kbRoot.toRealPath(); } catch (IOException e) { return kbRoot.toAbsolutePath().normalize(); }
    }

    /** 临时/备份文件(永不入库) */
    private static final Set<String> TEMP_EXTS = Set.of("tmp", "swp", "swo", "bak", "orig", "lock");

    /** 纯垃圾文件名判定(Office/LibreOffice 锁文件/AppleDouble/OS 垃圾/临时备份)——遍历时直接跳过,
        不占进度总数、不留失败记录(它们不是内容,失败列表只该留给真内容)。 */
    public boolean isJunkFileName(String name) {
        if (name == null) return false;
        String lower = name.toLowerCase(Locale.ROOT);
        if (name.startsWith(OFFICE_LOCK_PREFIX) || name.startsWith("._") || name.startsWith(".~lock.")) return true;
        if (OS_JUNK_NAMES.contains(lower)) return true;
        int dot = lower.lastIndexOf('.');
        return dot >= 0 && TEMP_EXTS.contains(lower.substring(dot + 1));
    }

    /** 逐文件判定（realPath 已解析符号链接）。 */
    public Verdict check(Path realPath, Path kbRoot) {
        kbRoot = canonicalRoot(kbRoot);
        realPath = canonicalRoot(realPath);
        if (!realPath.startsWith(kbRoot)) {
            return Verdict.denied("文件位于知识空间根目录之外");
        }
        String name = realPath.getFileName().toString();
        String lower = name.toLowerCase(Locale.ROOT);
        // Office 锁文件(~$xx.docx)与 macOS AppleDouble(._xx)与 OS 垃圾——非内容文件,直接忽略
        if (isJunkFileName(name)) {
            return Verdict.denied("Office 临时锁文件或系统文件");
        }
        if (DENIED_NAMES.contains(lower)) {
            return Verdict.denied("凭据/密钥类文件名");
        }
        int dot = lower.lastIndexOf('.');
        if (dot >= 0 && DENIED_EXTS.contains(lower.substring(dot + 1))) {
            return Verdict.denied("凭据/密钥类扩展名");
        }
        // 隐藏文件（.DS_Store/.xxx）默认跳过
        if (lower.startsWith(".")) {
            return Verdict.denied("隐藏文件");
        }
        // 路径段含忽略目录
        Path rel = kbRoot.relativize(realPath);
        for (Path seg : rel) {
            if (IGNORED_DIRS.contains(seg.toString().toLowerCase(Locale.ROOT))) {
                return Verdict.denied("构建/依赖/版本控制目录");
            }
        }
        // LocalPathGuard 敏感路径（realPath 已解析）
        if (sensitivePath.test(realPath.toString())) {
            return Verdict.denied("系统敏感路径");
        }
        return Verdict.ok();
    }

    /** 目录是否整个忽略（遍历时剪枝）。 */
    public boolean isIgnoredDir(Path dir, Path kbRoot) {
        kbRoot = canonicalRoot(kbRoot);
        String name = dir.getFileName() == null ? "" : dir.getFileName().toString().toLowerCase(Locale.ROOT);
        if (IGNORED_DIRS.contains(name)) return true;
        if (name.startsWith(".") && !dir.equals(kbRoot)) return true; // 隐藏目录
        return sensitivePath.test(dir.toString());
    }

    /** 读取 KB 根的 .knowledgeignore + .gitignore(同语法子集:每行相对路径前缀或 *.ext;# 注释)。
        知识库多收文档而 .gitignore 是为代码写的——两者合并取并集,语义都是「别收」。 */
    public List<String> readKnowledgeIgnore(Path kbRoot) {
        java.util.List<String> out = new java.util.ArrayList<>(readRulesFile(kbRoot.resolve(".knowledgeignore")));
        out.addAll(readRulesFile(kbRoot.resolve(".gitignore")));
        return List.copyOf(out);
    }

    private List<String> readRulesFile(Path f) {
        if (!Files.isRegularFile(f)) return List.of();
        try {
            return Files.readAllLines(f).stream()
                    .map(String::trim)
                    .filter(l -> !l.isEmpty() && !l.startsWith("#"))
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    /** .knowledgeignore 规则命中判定（前缀目录 或 *.ext）。 */
    public boolean ignoredByRules(Path realPath, Path kbRoot, List<String> rules) {
        Path rel = canonicalRoot(kbRoot).relativize(realPath);
        String relStr = rel.toString().replace('\\', '/');
        String name = realPath.getFileName().toString();
        for (String rule : rules) {
            if (rule.startsWith("*.")) {
                if (name.toLowerCase(Locale.ROOT).endsWith(rule.substring(1).toLowerCase(Locale.ROOT))) return true;
            } else {
                String r = rule.endsWith("/") ? rule : rule + "/";
                if ((relStr + "/").startsWith(r) || relStr.equals(rule)) return true;
            }
        }
        return false;
    }
}
