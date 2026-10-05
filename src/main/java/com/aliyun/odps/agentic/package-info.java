/**
 * Agentic Harness SDK 的主入口与公共 API。
 *
 * <p>本包包含 {@link com.aliyun.odps.agentic.HarnessEngine}，它是 SDK 的统一入口。
 * 用户通过创建引擎、定义 {@link com.aliyun.odps.agentic.agent.AgentDef} 并运行会话来使用本 SDK。
 *
 * <h2>包结构：</h2>
 * <ul>
 *   <li>{@code com.aliyun.odps.agentic} — 公共 API 入口</li>
 *   <li>{@code com.aliyun.odps.agentic.agent} — 代理 SPI（实现 {@code AgentDef}）</li>
 *   <li>{@code com.aliyun.odps.agentic.model} — 公共数据模型</li>
 *   <li>{@code com.aliyun.odps.agentic.llm} — LLM 集成（{@code LLMClient} 与转换器）</li>
 *   <li>{@code com.aliyun.odps.agentic.tool} — 工具 SPI（实现 {@code ToolDef} 以扩展自定义工具）</li>
 *   <li>{@code com.aliyun.odps.agentic.tool.builtin} — 内置工具实现</li>
 *   <li>{@code com.aliyun.odps.agentic.skill} — 技能 SPI</li>
 *   <li>{@code com.aliyun.odps.agentic.memory} — 记忆 SPI</li>
 *   <li>{@code com.aliyun.odps.agentic.permission} — 权限规则</li>
 *   <li>{@code com.aliyun.odps.agentic.session} — <b>内部实现</b>，不作为公共 API 使用</li>
 *   <li>{@code com.aliyun.odps.agentic.patch} — <b>内部实现</b>，不作为公共 API 使用</li>
 * </ul>
 */
package com.aliyun.odps.agentic;
