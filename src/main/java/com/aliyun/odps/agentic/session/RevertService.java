package com.aliyun.odps.agentic.session;

import com.aliyun.odps.agentic.model.Message;
import com.aliyun.odps.agentic.model.MessagePart;
import com.aliyun.odps.agentic.model.Role;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 文件回滚服务。
 *
 * <p>负责将工作目录恢复到代理执行某一步骤之前的状态，以及取消回滚操作。
 * 同时管理回滚信息的会话级存储。
 */
public class RevertService {

    /**
     * 快照策略接口。
     *
     * <p>提供文件系统快照的采集、恢复、差异和清理能力。
     */
    public interface SnapshotStrategy {
        /** 初始化快照存储。 */
        void init() throws Exception;
        /** 采集当前状态并返回快照 ID。 */
        Optional<String> track() throws Exception;
        /** 获取快照涉及的文件列表。 */
        SnapshotPatch patch(String hash) throws Exception;
        /** 将文件系统恢复到快照。 */
        void restore(String snapshot) throws Exception;
        /** 反向应用补丁列表以撤销文件变化。 */
        void revert(List<SnapshotPatch> patches) throws Exception;
        /** 计算快照差异文本。 */
        String diff(String hash) throws Exception;
        /** 清理过期快照。 */
        void cleanup() throws Exception;
        /** 检查策略是否可用。 */
        boolean isAvailable();
    }

    /**
     * 快照补丁信息。
     *
     * @param hash 快照哈希
     * @param files 涉及的文件列表
     */
    public record SnapshotPatch(
        String hash,
        List<String> files
    ) {}

    /**
     * 回滚信息。
     *
     * @param messageID 目标消息 ID
     * @param partID 目标片段 ID
     * @param snapshot 快照哈希
     * @param diff 差异文本
     * @param summary 差异统计摘要
     */
    public record RevertInfo(
        String messageID,
        String partID,
        String snapshot,
        String diff,
        DiffSummary summary
    ) {}

    /**
     * 差异统计摘要。
     *
     * @param additions 新增行数
     * @param deletions 删除行数
     * @param files 文件数
     */
    public record DiffSummary(
        int additions,
        int deletions,
        int files
    ) {}

    private final SnapshotStrategy strategy;
    private final MessageStore messageStore;
    private final Map<String, RevertInfo> sessionReverts = new ConcurrentHashMap<>();

    /**
     * 创建回滚服务。
     *
     * @param strategy 快照策略
     * @param messageStore 消息存储
     */
    public RevertService(SnapshotStrategy strategy, MessageStore messageStore) {
        this.strategy = strategy;
        this.messageStore = messageStore;
    }

    /**
     * 回滚到指定消息对应的工作目录状态。
     *
     * @param sessionID 会话 ID
     * @param messageID 目标消息 ID
     * @param partID 目标片段 ID；若为 {@code null} 则按消息级回滚
     * @return 回滚信息；未找到目标消息时返回 {@code null}
     */
    public RevertInfo revert(String sessionID, String messageID, String partID) throws Exception {
        List<Message> messages = messageStore.getMessages(sessionID);
        if (messages.isEmpty()) return null;

        // 确定回滚边界并收集后续补丁
        String revertMessageID = null;
        String revertPartID = partID;
        List<MessagePart.ToolCallPart> collectedPatches = new ArrayList<>();

        Message lastUser = null;
        for (Message msg : messages) {
            if (msg.role() == Role.USER) lastUser = msg;

            List<MessagePart> remaining = new ArrayList<>();
            for (MessagePart part : msg.parts()) {
                if (revertMessageID != null) {
                    if (part instanceof MessagePart.ToolCallPart tcp) {
                        collectedPatches.add(tcp);
                    }
                    continue;
                }

                if ((msg.id().equals(messageID) && partID == null) ||
                    (part instanceof MessagePart.ToolCallPart tcp && tcp.callID().equals(partID))) {
                    boolean hasTextOrTool = remaining.stream().anyMatch(p ->
                        p instanceof MessagePart.TextPart || p instanceof MessagePart.ToolCallPart);
                    revertPartID = hasTextOrTool ? partID : null;
                    revertMessageID = (revertPartID == null && lastUser != null) ? lastUser.id() : msg.id();
                }
                remaining.add(part);
            }
        }

        if (revertMessageID == null) return null;

        // 采集或复用快照
        RevertInfo existingRevert = sessionReverts.get(sessionID);
        String snapshotHash;
        if (existingRevert != null && existingRevert.snapshot() != null) {
            snapshotHash = existingRevert.snapshot();
            strategy.restore(snapshotHash);
        } else {
            snapshotHash = strategy.track().orElse(null);
        }

        // 反向应用后续补丁
        List<SnapshotPatch> snapshotPatches = new ArrayList<>();
        for (MessagePart.ToolCallPart tcp : collectedPatches) {
            snapshotPatches.add(new SnapshotPatch(tcp.callID(), List.of()));
        }
        if (!snapshotPatches.isEmpty()) {
            strategy.revert(snapshotPatches);
        }

        // 计算差异（尽力而为）
        String diffText = null;
        if (snapshotHash != null) {
            try {
                diffText = strategy.diff(snapshotHash);
            } catch (Exception e) {
                // 差异信息仅用于展示，失败不阻塞回滚。
            }
        }

        // 生成差异统计
        DiffSummary summary = null;
        if (diffText != null) {
            int additions = 0, deletions = 0, files = 0;
            for (String line : diffText.split("\n")) {
                if (line.startsWith("+") && !line.startsWith("++")) additions++;
                if (line.startsWith("-") && !line.startsWith("--")) deletions++;
                if (line.startsWith("diff --git")) files++;
            }
            summary = new DiffSummary(additions, deletions, files);
        }

        RevertInfo revertInfo = new RevertInfo(revertMessageID, revertPartID, snapshotHash, diffText, summary);
        sessionReverts.put(sessionID, revertInfo);
        return revertInfo;
    }

    /**
     * 取消回滚，恢复到执行回滚前的状态。
     *
     * @param sessionID 会话 ID
     * @return 被取消的回滚信息；若无活跃回滚则返回 {@code null}
     */
    public RevertInfo unrevert(String sessionID) throws Exception {
        RevertInfo revertInfo = sessionReverts.get(sessionID);
        if (revertInfo == null) return null;

        if (revertInfo.snapshot() != null) {
            strategy.restore(revertInfo.snapshot());
        }

        sessionReverts.remove(sessionID);
        return revertInfo;
    }

    /**
     * 获取会话当前的回滚信息。
     *
     * @param sessionID 会话 ID
     * @return 回滚信息；无活跃回滚时返回 {@code null}
     */
    public RevertInfo getRevertInfo(String sessionID) {
        return sessionReverts.get(sessionID);
    }

    /**
     * 清除会话的回滚信息但不恢复文件。
     *
     * @param sessionID 会话 ID
     */
    public void clearRevert(String sessionID) {
        sessionReverts.remove(sessionID);
    }

    /**
     * 获取当前使用的快照策略。
     *
     * @return 快照策略
     */
    public SnapshotStrategy getStrategy() {
        return strategy;
    }
}
