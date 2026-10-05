package com.aliyun.odps.agentic.patch;

/**
 * 文件变更类型枚举。
 * 表示补丁对文件执行的操作类型。
 */
public enum ChangeType {
    ADD("+"),
    UPDATE("~"),
    DELETE("-"),
    MOVE(">");

    private final String symbol;

    ChangeType(String symbol) {
        this.symbol = symbol;
    }

    /**
     * 获取变更类型对应的符号。
     *
     * @return 类型符号
     */
    public String symbol() {
        return symbol;
    }
}
