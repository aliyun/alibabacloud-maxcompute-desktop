package com.aliyun.odps.agentic.agent;

/**
 * 模型配置，用于标识代理应使用的 LLM。
 *
 * @param providerId LLM 提供者标识，例如 {@code "anthropic"}、{@code "openai"}、{@code "custom"}
 * @param modelId    提供者内部的模型标识，例如 {@code "claude-4-sonnet"}、{@code "gpt-4o"}
 */
public record ModelConfig(
    String providerId,
    String modelId
) {}
