package com.aliyun.odps.agentic.session;

import com.aliyun.odps.agentic.agent.AgentDef;
import com.aliyun.odps.agentic.model.Message;
import com.aliyun.odps.agentic.model.MessagePart;
import com.aliyun.odps.agentic.model.Session;

import java.util.List;

/**
 * Application-owned completion policy for a harness run.
 *
 * <p>The SDK owns the model/tool loop, while an embedding application may require
 * evidence or deliverables before accepting a model's final answer. The callback
 * receives a stable snapshot and must be free of message-store mutations. Returning
 * {@link FinishDecision#continueWith(String)} appends the feedback as a user message
 * and gives the model another step; the normal max-step limit still applies.</p>
 */
@FunctionalInterface
public interface RunPolicy {
    FinishDecision beforeFinish(Session session, AgentDef agent, Message candidate,
                                List<Message> history);

    /** Inspect a fully persisted tool batch before the next model call. */
    default ToolBatchDecision afterToolBatch(Session session, AgentDef agent,
                                             Message assistant,
                                             List<MessagePart.ToolResultPart> results,
                                             List<Message> history) {
        return ToolBatchDecision.continueRun();
    }

    /** Host-planned tool calls to execute without spending a model round trip. */
    default List<MessagePart.ToolCallPart> beforeModelCall(Session session, AgentDef agent,
                                                           List<Message> history) {
        return List.of();
    }

    /** New observations that must enter history before planning or the model call. */
    default List<List<MessagePart>> beforeStep(Session session, AgentDef agent,
                                               List<Message> history) {
        return List.of();
    }

    /** Request a tool-free closing model call at the next step boundary. */
    default Finalization beforeModelCallFinalization(Session session, AgentDef agent,
                                                     List<Message> history) {
        return null;
    }

    /** Inspect model-proposed tools before persisting any calls or executing them. */
    default Finalization beforeToolBatch(Session session, AgentDef agent,
                                         Message candidate, List<Message> history) {
        return null;
    }

    /** Host termination code after a policy-requested closing answer. */
    default String terminationReason() { return null; }

    /**
     * Use the SDK's generic repeated-tool guard. A host with its own progress-aware
     * {@link #beforeToolBatch} policy may disable this guard for the run.
     */
    default boolean useBuiltinDoomLoopDetection() { return true; }

    record Finalization(String reason, String hint) {
        public Finalization {
            if (reason == null || reason.isBlank() || hint == null || hint.isBlank()) {
                throw new IllegalArgumentException("Finalization requires reason and hint");
            }
        }
    }

    record ToolBatchDecision(String finalText, String suspendReason, String feedback,
                             List<MessagePart> followupParts) {
        public ToolBatchDecision {
            followupParts = followupParts == null ? List.of() : List.copyOf(followupParts);
            if (finalText != null && finalText.isBlank()) {
                throw new IllegalArgumentException("Final text cannot be blank");
            }
            if (suspendReason != null && suspendReason.isBlank()) {
                throw new IllegalArgumentException("Suspend reason cannot be blank");
            }
            if (finalText != null && suspendReason != null) {
                throw new IllegalArgumentException("Tool batch cannot finish and suspend together");
            }
            if (feedback != null && (feedback.isBlank()
                    || finalText != null || suspendReason != null)) {
                throw new IllegalArgumentException("Tool feedback must be nonblank and exclusive");
            }
        }
        public static ToolBatchDecision continueRun() {
            return new ToolBatchDecision(null, null, null, List.of());
        }
        public static ToolBatchDecision finishWith(String text) {
            return new ToolBatchDecision(text, null, null, List.of());
        }
        public static ToolBatchDecision suspend(String reason) {
            return new ToolBatchDecision(null, reason, null, List.of());
        }
        public static ToolBatchDecision continueWith(String feedback) {
            return new ToolBatchDecision(null, null, feedback, List.of());
        }
        public ToolBatchDecision withFollowupParts(List<MessagePart> parts) {
            return new ToolBatchDecision(finalText, suspendReason, feedback, parts);
        }
    }

    record FinishDecision(boolean accepted, String feedback, String fallbackText,
                          String suspendReason, List<MessagePart> replacementParts) {
        public FinishDecision {
            if (!accepted && suspendReason == null && (feedback == null || feedback.isBlank())) {
                throw new IllegalArgumentException("Rejected finish requires non-blank feedback");
            }
            if (suspendReason != null && suspendReason.isBlank()) {
                throw new IllegalArgumentException("Suspend reason cannot be blank");
            }
            replacementParts = replacementParts == null ? null : List.copyOf(replacementParts);
            if (replacementParts != null && !accepted) {
                throw new IllegalArgumentException("Only an accepted finish can replace parts");
            }
        }

        /** Preserve the original four-argument constructor for SDK callers. */
        public FinishDecision(boolean accepted, String feedback, String fallbackText,
                              String suspendReason) {
            this(accepted, feedback, fallbackText, suspendReason, null);
        }

        public boolean suspended() { return suspendReason != null; }

        public static FinishDecision accept() {
            return new FinishDecision(true, null, null, null);
        }

        /** Fill an otherwise empty final answer with application-verified text. */
        public static FinishDecision acceptWithTextIfEmpty(String text) {
            if (text == null || text.isBlank()) {
                throw new IllegalArgumentException("Fallback final text cannot be blank");
            }
            return new FinishDecision(true, null, text, null);
        }

        /** Replace an accepted final message while retaining non-text parts and SDK history identity. */
        public static FinishDecision acceptWithParts(List<MessagePart> parts) {
            if (parts == null || parts.isEmpty()) {
                throw new IllegalArgumentException("Replacement final parts cannot be empty");
            }
            return new FinishDecision(true, null, null, null, parts);
        }

        public static FinishDecision continueWith(String feedback) {
            return new FinishDecision(false, feedback, null, null);
        }

        /** Release the worker while the host waits for an external result. */
        public static FinishDecision suspend(String reason) {
            return new FinishDecision(false, null, null, reason);
        }
    }
}
