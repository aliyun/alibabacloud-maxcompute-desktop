package com.aliyun.odps.agentic.config;

/**
 * Prompt locale for cached system prompt rendering (Task 2b).
 *
 * <p>Selects which language variant the {@code PromptManager} emits for
 * high-frequency cached sections (role, rules, completion, grounding,
 * analytical reasoning, parallel actions, etc.) and uncached thought-prompt
 * structural labels.</p>
 *
 * <ul>
 *   <li>{@link #CN}: legacy Chinese rendering. Preserved for back-compat and
 *       fallback when the English path produces unexpected output.</li>
 *   <li>{@link #EN}: English rendering. Reduces cached prompt token footprint
 *       by ~60%. Structural labels used by downstream extractors (e.g.
 *       {@code [假设H1]}, {@code [结论C1]}, {@code fast/normal}) remain in
 *       their original form regardless of locale.</li>
 * </ul>
 */
public enum PromptLocale {
    CN,
    EN;

    /** Resolve from a configuration string; defaults to {@link #EN}. */
    public static PromptLocale fromConfig(String raw) {
        if (raw == null) return EN;
        String v = raw.trim().toUpperCase();
        return "CN".equals(v) || "ZH".equals(v) || "ZH_CN".equals(v) ? CN : EN;
    }

    /**
     * Detect the language of a user message via a CJK-character-ratio heuristic.
     *
     * <p>Lightweight, dependency-free: if CJK characters make up more than
     * {@code CJK_RATIO_THRESHOLD} of the letter-like characters, the text is
     * treated as Chinese, otherwise English. Empty / null / punctuation-only
     * input falls back to {@link #EN} (matches the server-wide default).</p>
     */
    public static PromptLocale detect(String text) {
        if (text == null || text.isBlank()) return EN;
        int cjk = 0;
        int letters = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (isCjk(cp)) {
                cjk++;
                letters++;
            } else if (Character.isLetter(cp)) {
                letters++;
            }
        }
        if (letters == 0) return EN;
        return ((double) cjk / letters) > CJK_RATIO_THRESHOLD ? CN : EN;
    }

    /** Above this CJK-to-letter ratio, treat the message as Chinese. */
    private static final double CJK_RATIO_THRESHOLD = 0.2;

    private static boolean isCjk(int cp) {
        Character.UnicodeScript script = Character.UnicodeScript.of(cp);
        return script == Character.UnicodeScript.HAN
            || script == Character.UnicodeScript.HIRAGANA
            || script == Character.UnicodeScript.KATAKANA
            || script == Character.UnicodeScript.HANGUL;
    }

    public boolean isEnglish() { return this == EN; }
    public boolean isChinese() { return this == CN; }
}
