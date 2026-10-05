package com.aliyun.odps.agentic.session;

import com.aliyun.odps.agentic.model.MessagePart;

import java.util.List;
import java.util.function.Consumer;

/**
 * Application-owned tool execution boundary for one agent run.
 *
 * <p>The result list must contain exactly one result for each call, in input order.
 * Implementations may execute calls concurrently and report each completed result
 * through {@code onEarlyComplete}. The run loop persists results only after the
 * complete ordered batch is returned.</p>
 */
@FunctionalInterface
public interface ToolBatchExecutor {
    List<MessagePart.ToolResultPart> execute(
        List<MessagePart.ToolCallPart> calls,
        Consumer<MessagePart.ToolResultPart> onEarlyComplete);
}
