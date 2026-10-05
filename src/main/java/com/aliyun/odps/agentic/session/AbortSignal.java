package com.aliyun.odps.agentic.session;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 中止信号，用于取消正在执行的会话、工具或流式处理。
 *
 * <p>该类支持父子信号级联，便于在长时间运行的流程中统一传播取消状态。
 */
public class AbortSignal {

    private final AtomicBoolean aborted = new AtomicBoolean(false);
    private final AtomicReference<String> reason = new AtomicReference<>();
    private final AbortSignal parent;

    /**
     * 创建一个独立的中止信号。
     */
    public AbortSignal() {
        this(null);
    }

    /**
     * 创建一个带父信号的中止信号。
     *
     * @param parent 父级中止信号
     */
    public AbortSignal(AbortSignal parent) {
        this.parent = parent;
    }

    /**
     * 判断当前信号是否已中止。
     * 若存在父信号，也会一并检查父信号状态。
     *
     * @return 已中止返回 {@code true}
     */
    public boolean isAborted() {
        return aborted.get() || (parent != null && parent.isAborted());
    }

    /**
     * 获取中止原因。
     * 当前信号没有原因时会回退到父信号。
     *
     * @return 中止原因；若未中止则可能为 {@code null}
     */
    public String getReason() {
        String r = reason.get();
        if (r != null) return r;
        if (parent != null) return parent.getReason();
        return null;
    }

    /**
     * 使用默认原因中止信号。
     */
    public void abort() {
        abort("User cancelled");
    }

    /**
     * 使用指定原因中止信号。
     *
     * @param reason 中止原因
     */
    public void abort(String reason) {
        aborted.set(true);
        this.reason.set(reason);
    }

    /**
     * 在信号已中止时抛出异常。
     * 适合在长流程的阶段边界作为检查点调用。
     */
    public void throwIfAborted() {
        if (isAborted()) {
            throw new AbortedException(getReason());
        }
    }

    /**
     * 创建子信号。
     * 子信号会继承父信号的中止状态，但不会反向影响父信号。
     *
     * @return 新的子中止信号
     */
    public AbortSignal createChild() {
        return new AbortSignal(this);
    }

    /**
     * 重置信号状态，便于复用。
     */
    public void reset() {
        aborted.set(false);
        reason.set(null);
    }

    /**
     * 表示操作被中止的运行时异常。
     */
    public static class AbortedException extends RuntimeException {
        /**
         * 使用给定原因创建异常。
         *
         * @param reason 中止原因
         */
        public AbortedException(String reason) {
            super("Operation aborted: " + reason);
        }
    }
}
