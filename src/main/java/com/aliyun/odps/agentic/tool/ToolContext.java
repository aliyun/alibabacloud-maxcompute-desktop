package com.aliyun.odps.agentic.tool;

import com.aliyun.odps.agentic.model.Message;
import com.aliyun.odps.agentic.model.MessagePart;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 工具执行上下文，为工具实现提供运行时信息。
 * 包含会话、消息、权限询问和进度上报等回调。
 *
 * @param sessionId        当前会话 ID
 * @param messageId        当前消息 ID
 * @param agent            代理名称
 * @param callId           工具调用 ID
 * @param messages         会话中可用的消息列表
 * @param extra            附加上下文数据
 * @param metadataUpdater  更新消息元数据的回调
 * @param permissionAsker  向用户请求权限的回调
 * @param progressReporter 上报工具执行中间进度的回调
 */
public record ToolContext(
    String sessionId,
    String messageId,
    String agent,
    String callId,
    List<Message> messages,
    Map<String, Object> extra,
    Consumer<Map<String, Object>> metadataUpdater,
    PermissionAsker permissionAsker,
    ProgressReporter progressReporter
) {
    /**
     * 权限询问函数式接口，用于在工具执行前请求用户确认。
     */
    @FunctionalInterface
    public interface PermissionAsker {
        /**
         * 请求用户授予指定权限。
         *
         * @param permission 权限名称
         * @param target      权限目标
         * @param description 权限说明
         * @return 用户是否允许
         */
        boolean ask(String permission, String target, String description);
    }

    /**
     * 工具进度上报器。
     * 工具在执行过程中调用此接口上报中间进度，每次调用触发一个工具调用进度事件。
     */
    @FunctionalInterface
    public interface ProgressReporter {
        /**
         * 上报一次工具执行进度。
         *
         * @param title    进度标题
         * @param metadata 进度元数据
         */
        void report(String title, Map<String, Object> metadata);
    }

    /**
     * 向后兼容的工厂方法，不包含 {@code progressReporter}。
     */
    public static ToolContext of(String sessionId, String messageId, String agent, String callId,
                                  List<Message> messages, Map<String, Object> extra,
                                  Consumer<Map<String, Object>> metadataUpdater,
                                  PermissionAsker permissionAsker) {
        return new ToolContext(sessionId, messageId, agent, callId, messages, extra,
                               metadataUpdater, permissionAsker, null);
    }
}
