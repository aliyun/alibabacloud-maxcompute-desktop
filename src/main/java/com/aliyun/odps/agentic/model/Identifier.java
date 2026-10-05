package com.aliyun.odps.agentic.model;

/**
 * 单调递增的 ID 生成器，用于生成可排序的标识符。
 * 生成的 ID 同时兼顾时间顺序与随机性，适合消息、会话和工具调用等场景。
 */
public final class Identifier {

    private Identifier() {}

    private static final String CHARS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    private static final int LENGTH = 26;

    private static long lastTimestamp = 0;
    private static int counter = 0;

    // ── 前缀常量 ─────────────────────────────────

    public static final String PREFIX_MESSAGE = "msg";
    public static final String PREFIX_PART = "prt";
    public static final String PREFIX_SESSION = "ses";
    public static final String PREFIX_PERMISSION = "per";
    public static final String PREFIX_QUESTION = "que";
    public static final String PREFIX_TOOL = "tool";
    public static final String PREFIX_JOB = "job";
    public static final String PREFIX_EVENT = "evt";

    /**
     * 生成按时间递增排序的 ID。
     *
     * @param prefix ID 前缀
     * @return 递增 ID
     */
    public static synchronized String ascending(String prefix) {
        return create(prefix, true);
    }

    /**
     * 生成按时间递减排序的 ID。
     *
     * @param prefix ID 前缀
     * @return 递减 ID
     */
    public static synchronized String descending(String prefix) {
        return create(prefix, false);
    }

    /**
     * 生成消息 ID。
     *
     * @return 消息 ID
     */
    public static String messageId() {
        return ascending(PREFIX_MESSAGE);
    }

    /**
     * 生成消息片段 ID。
     *
     * @return 消息片段 ID
     */
    public static String partId() {
        return ascending(PREFIX_PART);
    }

    /**
     * 生成会话 ID。
     *
     * @return 会话 ID
     */
    public static String sessionId() {
        return ascending(PREFIX_SESSION);
    }

    /**
     * 从递增 ID 中提取时间戳。
     *
     * @param id 标识符
     * @return 时间戳毫秒值
     */
    public static long timestamp(String id) {
        int underscoreIdx = id.indexOf('_');
        if (underscoreIdx < 0) throw new IllegalArgumentException("Invalid ID format: " + id);
        String hex = id.substring(underscoreIdx + 1, Math.min(underscoreIdx + 13, id.length()));
        long encoded = Long.parseUnsignedLong(hex, 16);
        return encoded / 0x1000;
    }

    // ── 内部实现 ─────────────────────────────────

    private static String create(String prefix, boolean ascending) {
        long currentTimestamp = System.currentTimeMillis();

        if (currentTimestamp != lastTimestamp) {
            lastTimestamp = currentTimestamp;
            counter = 0;
        }
        counter++;

        long now = currentTimestamp * 0x1000L + counter;

        if (!ascending) {
            now = ~now;
        }

        StringBuilder hex = new StringBuilder(12);
        for (int i = 5; i >= 0; i--) {
            int b = (int) ((now >> (40 - 8 * i)) & 0xff);
            hex.append(String.format("%02x", b));
        }

        int randomLen = LENGTH - 12;
        StringBuilder random = new StringBuilder(randomLen);
        for (int i = 0; i < randomLen; i++) {
            random.append(CHARS.charAt((int) (Math.random() * 62)));
        }

        return prefix + "_" + hex + random;
    }
}
