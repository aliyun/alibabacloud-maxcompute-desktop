package com.aliyun.odps.agentic.memory.knowledge;


import java.util.ArrayList;
import java.util.List;

/**
 * 标题感知切块器（设计文档 §5.5 + 主题层输入）。
 *
 * <p>规则：标题行（抽取器给的 headings 在原文中的出现位置）起新块；段落优先边界；
 * 目标 500–800 字符，overlap 80；每块带 header_path（最近标题链）与 char 偏移。</p>
 */
public class Chunker {

    public static final int TARGET_SIZE = 680;
    public static final int MAX_SIZE = 800;
    public static final int MIN_SIZE = 20;
    public static final int OVERLAP = 80;

    public record Chunk(String content, String headerPath, int charStart, int charEnd) {}

    public List<Chunk> chunk(String text, List<String> headings) {
        List<Chunk> out = new ArrayList<>();
        if (text == null || text.isBlank()) return out;

        List<String> paras = new ArrayList<>();
        for (String p : text.split("\n")) {
            if (!p.trim().isEmpty()) paras.add(p.trim());
        }

        StringBuilder cur = new StringBuilder();
        String currentHeader = "";
        int curStart = 0;
        int cursor = 0; // 近似字符偏移（按段落顺序推进，trim 后有少量误差，仅用于预览定位）
        for (String para : paras) {
            // 标题判定:剥掉 markdown 的 # 前缀后比对(extractPlain 的 headings 已剥 #)
            String paraNorm = para.replaceFirst("^#+\\s*", "");
            boolean isHeading = headings != null && (headings.contains(para) || headings.contains(paraNorm));
            if (isHeading) para = paraNorm;
            if (isHeading) {
                // 标题边界:cur 非空即 flush——标题行本身很短,用 MIN_SIZE 会把连续标题的内容丢掉
                if (cur.length() > 0) {
                    out.add(finish(cur, currentHeader, curStart, cursor));
                    cur = new StringBuilder();
                    curStart = cursor;
                }
                currentHeader = para;
            }
            // 超长段落:先按句子边界(。!?;;)再切,仍超才段落整体保留(不切句中)
            List<String> pieces = para.length() > MAX_SIZE ? splitSentences(para) : List.of(para);
            for (String piece : pieces) {
                if (cur.length() + piece.length() > MAX_SIZE && cur.length() >= MIN_SIZE) {
                    out.add(finish(cur, currentHeader, curStart, cursor));
                    // overlap 取尾部最近句子边界,不再字符硬切
                    String tail = sentenceAwareTail(cur.toString(), OVERLAP);
                    cur = new StringBuilder(tail);
                    curStart = Math.max(0, cursor - tail.length());
                }
                if (!cur.isEmpty()) cur.append('\n');
                cur.append(piece);
                cursor += piece.length() + 1;
            }
        }
        // 收尾块:非空即收(MIN_SIZE 只拦段落切分碎屑;短文档整块<MIN 也必须入库,否则内容静默消失)
        if (cur.length() > 0) {
            out.add(finish(cur, currentHeader, curStart, cursor));
        }
        return out;
    }

    /** 句子边界切分:。!?;; 后断;过短句并入下一刀。 */
    private static List<String> splitSentences(String para) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String seg : para.split("(?<=[。!?;;])|(?<=[.!?])\\s+")) {
            if (cur.length() + seg.length() > MAX_SIZE && cur.length() >= MIN_SIZE) {
                out.add(cur.toString());
                cur = new StringBuilder();
            }
            cur.append(seg);
        }
        if (!cur.isEmpty()) out.add(cur.toString());
        return out;
    }

    /** overlap 尾巴对齐到最近句子边界;找不到(无标点长句)才退回字符切。 */
    private static String sentenceAwareTail(String text, int overlap) {
        if (text.length() <= overlap) return text;
        String tail = text.substring(text.length() - overlap);
        for (String sep : List.of("。", "!", "?", "!", "?", ";", ";", ". ", "\n")) {
            int at = tail.indexOf(sep);
            if (at >= 0 && at + sep.length() < tail.length()) {
                return tail.substring(at + sep.length());
            }
        }
        return tail;
    }

    private Chunk finish(StringBuilder cur, String header, int start, int end) {
        return new Chunk(cur.toString(), header == null || header.isBlank() ? null : header, start, end);
    }
}
