package com.aliyun.odps.agentic.session;

/** Raised by legacy message-only run APIs when a host policy pauses the run. */
public final class RunSuspendedException extends IllegalStateException {
    private final String reason;

    public RunSuspendedException(String reason) {
        super("Agent run suspended: " + reason);
        this.reason = reason;
    }

    public String reason() { return reason; }
}
