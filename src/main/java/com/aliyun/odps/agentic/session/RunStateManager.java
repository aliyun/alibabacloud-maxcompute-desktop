package com.aliyun.odps.agentic.session;

/**
 * 运行状态管理器，用于跟踪会话是否忙碌以及是否收到取消请求。
 *
 * <p>该类提供简单的并发可见性语义，适合在运行循环外围协调状态。
 */
public class RunStateManager {

    private volatile boolean busy = false;
    private volatile boolean cancelRequested = false;

    /**
     * 将会话标记为忙碌状态，并清除取消请求。
     */
    public void setBusy() {
        this.busy = true;
        this.cancelRequested = false;
    }

    /**
     * 将会话标记为空闲状态，并清除取消请求。
     */
    public void setIdle() {
        this.busy = false;
        this.cancelRequested = false;
    }

    /**
     * 请求取消当前运行。
     */
    public void requestCancel() {
        this.cancelRequested = true;
    }

    /**
     * 判断是否已请求取消。
     *
     * @return 已请求取消返回 {@code true}
     */
    public boolean isCancelRequested() {
        return cancelRequested;
    }

    /**
     * 判断会话当前是否处于忙碌状态。
     *
     * @return 忙碌返回 {@code true}
     */
    public boolean isBusy() {
        return busy;
    }
}
