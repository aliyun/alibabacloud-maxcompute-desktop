/**
 * 代理 SPI，定义代理的行为、能力和运行配置。
 *
 * <p>用户通过实现 {@link com.aliyun.odps.agentic.agent.AgentDef} 来定义自己的代理。
 * Harness SDK 提供固定运行时，而 {@code AgentDef} 负责定义代理本身。
 *
 * <p>最小代理实现示例：
 * <pre>{@code
 * class MyAgent implements AgentDef {
 *     public String getName() { return "my-agent"; }
 *     public String getSystemPrompt(Function<String, String> mp) {
 *         return "You are a helpful coding assistant.";
 *     }
 * }
 * }</pre>
 */
package com.aliyun.odps.agentic.agent;
