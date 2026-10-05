package com.aliyun.odps.agentic.model;

import com.aliyun.odps.agentic.llm.Model;
import com.aliyun.odps.agentic.llm.Usage;
import com.aliyun.odps.agentic.permission.Rule;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 会话记录，表示一次完整的对话会话。
 * 它包含消息历史、模型、成本、Token 统计以及权限配置等运行信息。
 *
 * @param id        会话 ID
 * @param title     会话标题
 * @param status    会话状态
 * @param messages  消息历史
 * @param agent     代理名称
 * @param model     会话绑定的模型
 * @param cost      累计成本
 * @param tokens    累计 Token 使用情况
 * @param createdAt 创建时间
 * @param updatedAt 最后更新时间
 * @param permission 权限规则列表
 */
public record Session(
    String id,
    String title,
    SessionStatus status,
    List<Message> messages,
    String agent,
    Model model,
    Double cost,
    Tokens tokens,
    Instant createdAt,
    Instant updatedAt,
    List<Rule> permission
) {
    /**
     * 创建一个最小化会话对象。
     *
     * @param id    会话 ID
     * @param title 会话标题
     */
    public Session(String id, String title) {
        this(id, title, SessionStatus.IDLE, List.of(), null, null, 0.0, Tokens.empty(), Instant.now(), Instant.now(), null);
    }

    /**
     * 返回更新状态后的新会话对象。
     *
     * @param newStatus 新状态
     * @return 新会话对象
     */
    public Session withStatus(SessionStatus newStatus) {
        return new Session(id, title, newStatus, messages, agent, model, cost, tokens, createdAt, Instant.now(), permission);
    }

    /**
     * 返回更新标题后的新会话对象。
     *
     * @param newTitle 新标题
     * @return 新会话对象
     */
    public Session withTitle(String newTitle) {
        return new Session(id, newTitle, status, messages, agent, model, cost, tokens, createdAt, Instant.now(), permission);
    }

    /**
     * 返回替换消息历史后的新会话对象。
     *
     * @param newMessages 新消息列表
     * @return 新会话对象
     */
    public Session withMessages(List<Message> newMessages) {
        return new Session(id, title, status, newMessages, agent, model, cost, tokens, createdAt, Instant.now(), permission);
    }

    /**
     * 切换当前会话绑定的模型。
     *
     * @param newModel 新模型
     * @return 绑定新模型的会话对象
     */
    public Session withModel(Model newModel) {
        return new Session(id, title, status, messages, agent, newModel, cost, tokens, createdAt, Instant.now(), permission);
    }

    // ── 成本跟踪 ────────────────────────────────

    /**
     * 累加单步执行产生的成本。
     *
     * @param additionalCost 新增成本
     * @return 新会话对象
     */
    public Session addCost(double additionalCost) {
        double newCost = (cost != null ? cost : 0.0) + additionalCost;
        return new Session(id, title, status, messages, agent, model, newCost, tokens, createdAt, Instant.now(), permission);
    }

    /**
     * 累加单步执行产生的 Token 使用量。
     *
     * @param additionalTokens 新增 Token 统计
     * @return 新会话对象
     */
    public Session addTokens(Tokens additionalTokens) {
        Tokens newTokens = tokens != null ? tokens.add(additionalTokens) : additionalTokens;
        return new Session(id, title, status, messages, agent, model, cost, newTokens, createdAt, Instant.now(), permission);
    }

    /**
     * 同时累加成本和 Token 使用量。
     *
     * @param stepCost   单步成本
     * @param stepTokens 单步 Token 统计
     * @return 新会话对象
     */
    public Session addUsage(double stepCost, Tokens stepTokens) {
        double newCost = (cost != null ? cost : 0.0) + stepCost;
        Tokens newTokens = tokens != null ? tokens.add(stepTokens) : stepTokens;
        return new Session(id, title, status, messages, agent, model, newCost, newTokens, createdAt, Instant.now(), permission);
    }

    // ── 撤销操作 ────────────────────────────────

    /**
     * 撤销最后一个助手回合及其后续工具结果消息。
     *
     * @return 移除最后一个助手回合后的新会话对象
     */
    public Session undoLastAssistantTurn() {
        if (messages == null || messages.isEmpty()) return this;

        List<Message> newMessages = new ArrayList<>(messages);

        while (!newMessages.isEmpty()) {
            Message last = newMessages.getLast();
            if (last.role() == Role.USER && last.parentMessageId() != null) {
                newMessages.removeLast();
            } else if (last.role() == Role.ASSISTANT) {
                newMessages.removeLast();
                break;
            } else {
                break;
            }
        }

        if (newMessages.size() == messages.size()) {
            return this;
        }

        return new Session(id, title, status, newMessages, agent, model, cost, tokens, createdAt, Instant.now(), permission);
    }

    /**
     * 根据 LLM 使用量和模型费率计算成本。
     *
     * @param usage 原始 LLM 使用量
     * @param model 模型信息
     * @return 计算得到的美元成本
     */
    public static double calculateCost(Usage usage, Model model) {
        if (usage == null) return 0.0;

        double inputRate = 3.0;
        double outputRate = 15.0;
        double cacheReadRate = 0.3;
        double cacheWriteRate = 3.75;

        int cacheRead = Math.max(0, usage.cacheReadInputTokens());
        int cacheWrite = Math.max(0, usage.cacheCreationInputTokens());
        int adjustedInput = Math.max(0, usage.inputTokens() - cacheRead - cacheWrite);
        int output = Math.max(0, usage.outputTokens());

        return (adjustedInput * inputRate + output * outputRate
                + cacheRead * cacheReadRate + cacheWrite * cacheWriteRate) / 1_000_000.0;
    }
}
