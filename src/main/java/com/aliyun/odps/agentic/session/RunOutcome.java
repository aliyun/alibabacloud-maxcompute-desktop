package com.aliyun.odps.agentic.session;

import com.aliyun.odps.agentic.model.Message;

/** Terminal message or a host-managed pause point. */
public record RunOutcome(Message message, boolean suspended, String suspendReason) {
    public static RunOutcome completed(Message message) {
        return new RunOutcome(message, false, null);
    }

    public static RunOutcome suspended(Message message, String reason) {
        return new RunOutcome(message, true, reason);
    }
}
