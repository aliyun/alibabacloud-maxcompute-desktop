package com.aliyun.odps.agentic.tool.schema;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Layered English description profile for a single tool.
 *
 * <p>Task 2: provides three verbosity levels so that {@code ToolDefinitionFactory}
 * can emit compact tool schemas to the LLM API (dramatically reducing tokens
 * compared to the verbose Chinese descriptions shipped with each {@link com.aliyun.odps.agentic.tool.ContextTool}).</p>
 *
 * <ul>
 *   <li>{@code descriptionL2} - full English description (professional translation
 *       of the original Chinese description, used for first-use and repair).</li>
 *   <li>{@code descriptionL1} - one-sentence English summary (≤ 20 words) used
 *       for steady-state turns.</li>
 *   <li>{@code descriptionL0} - may be omitted when the function name is
 *       self-explanatory.</li>
 * </ul>
 *
 * <p>Parameter descriptions can also be layered via {@link #paramDescL2} and
 * {@link #paramDescL1}. When a parameter entry is absent at a given level, the
 * factory falls back to the next higher level.</p>
 *
 * <p>Chinese comments are preserved in Java sources for developer reference but
 * are never shipped to the model at the English levels.</p>
 */
public final class ToolSchemaProfile {

    /** Schema version; bump when the profile payload changes to invalidate caches. */
    private final int schemaVersion;

    /** Full English description. Required; never null. */
    private final String descriptionL2;

    /** One-sentence English summary. Optional; falls back to descriptionL2. */
    private final String descriptionL1;

    /** Ultra-minimal label or null. */
    private final String descriptionL0;

    /** Parameter English descriptions at full verbosity. Empty map is legal. */
    private final Map<String, String> paramDescL2;

    /** Parameter English descriptions at compact verbosity. Empty map means reuse L2. */
    private final Map<String, String> paramDescL1;

    private ToolSchemaProfile(int schemaVersion,
                              String descriptionL2,
                              String descriptionL1,
                              String descriptionL0,
                              Map<String, String> paramDescL2,
                              Map<String, String> paramDescL1) {
        this.schemaVersion = schemaVersion;
        this.descriptionL2 = descriptionL2 == null ? "" : descriptionL2;
        this.descriptionL1 = descriptionL1;
        this.descriptionL0 = descriptionL0;
        this.paramDescL2 = paramDescL2 == null ? Collections.emptyMap()
                : Collections.unmodifiableMap(new HashMap<>(paramDescL2));
        this.paramDescL1 = paramDescL1 == null ? Collections.emptyMap()
                : Collections.unmodifiableMap(new HashMap<>(paramDescL1));
    }

    public int getSchemaVersion() { return schemaVersion; }

    /**
     * Description for the given level, with safe fallbacks.
     */
    public String descriptionFor(ToolSchemaLevel level) {
        switch (level) {
            case L0_MINIMAL:
                return descriptionL0 == null ? "" : descriptionL0;
            case L1_COMPACT:
                return descriptionL1 != null ? descriptionL1 : descriptionL2;
            case L2_FULL:
            default:
                return descriptionL2;
        }
    }

    /**
     * Parameter description with fallback chain: requested level -> L2 -> empty.
     */
    public String paramDescriptionFor(String paramName, ToolSchemaLevel level) {
        if (paramName == null) return "";
        switch (level) {
            case L0_MINIMAL:
                return ""; // minimal: no description at all
            case L1_COMPACT: {
                String v = paramDescL1.get(paramName);
                if (v != null) return v;
                v = paramDescL2.get(paramName);
                return v == null ? "" : v;
            }
            case L2_FULL:
            default: {
                String v = paramDescL2.get(paramName);
                return v == null ? "" : v;
            }
        }
    }

    public boolean hasL0Label() { return descriptionL0 != null && !descriptionL0.isEmpty(); }

    public static Builder builder() { return new Builder(); }

    /**
     * Fallback profile derived from the raw (often Chinese) skill description.
     * Takes the first sentence/line as a lossy L1 summary. L2 reuses the full
     * original text. This path does NOT enjoy the ~65% token saving of a hand
     * written English profile, but keeps uncovered tools functional.
     */
    public static ToolSchemaProfile fallbackFromRaw(String toolName, String rawDescription) {
        String full = rawDescription == null ? (toolName == null ? "" : toolName) : rawDescription;
        String compact = firstLineOrSentence(full);
        return new Builder()
                .schemaVersion(0)
                .descriptionL2(full)
                .descriptionL1(compact)
                .build();
    }

    private static String firstLineOrSentence(String text) {
        if (text == null || text.isEmpty()) return "";
        int newline = text.indexOf('\n');
        String firstLine = newline > 0 ? text.substring(0, newline) : text;
        // Also cut at Chinese period / semicolon / English full stop for safety.
        int cut = firstLine.length();
        for (char c : new char[] {'。', '；', '.', ';'}) {
            int idx = firstLine.indexOf(c);
            if (idx > 0 && idx < cut) cut = idx + 1;
        }
        return firstLine.substring(0, Math.min(cut, firstLine.length())).trim();
    }

    public static final class Builder {
        private int schemaVersion = 1;
        private String descriptionL2 = "";
        private String descriptionL1;
        private String descriptionL0;
        private final Map<String, String> paramDescL2 = new HashMap<>();
        private final Map<String, String> paramDescL1 = new HashMap<>();

        public Builder schemaVersion(int v) { this.schemaVersion = v; return this; }
        public Builder descriptionL2(String s) { this.descriptionL2 = s; return this; }
        public Builder descriptionL1(String s) { this.descriptionL1 = s; return this; }
        public Builder descriptionL0(String s) { this.descriptionL0 = s; return this; }
        public Builder paramL2(String name, String desc) { paramDescL2.put(name, desc); return this; }
        public Builder paramL1(String name, String desc) { paramDescL1.put(name, desc); return this; }

        public ToolSchemaProfile build() {
            return new ToolSchemaProfile(schemaVersion, descriptionL2, descriptionL1, descriptionL0,
                    paramDescL2, paramDescL1);
        }
    }
}
