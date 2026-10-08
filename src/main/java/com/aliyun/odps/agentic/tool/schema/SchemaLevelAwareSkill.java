package com.aliyun.odps.agentic.tool.schema;

import java.util.Map;

/**
 * Skill whose tool-definition CONTENT differs per {@link ToolSchemaLevel}, beyond the
 * generic description-stripping ToolDefinitionFactory applies (e.g. the {@code aigc}
 * tool exposes only lifecycle actions at L0_MINIMAL and the full action set at L2_FULL).
 *
 * <p>ToolDefinitionFactory 的缓存 key 已含 level，因此同一工具的两个版本天然分开缓存，
 * 且各自 JSON 字节级稳定（DashScope prefix cache 要求）。</p>
 */
public interface SchemaLevelAwareSkill {

    /**
     * Build the raw tool definition ({@code {type:function, function:{name,description,parameters}}})
     * for the given schema level. Must return stable, deterministically-ordered maps.
     */
    Map<String, Object> toToolDefinition(ToolSchemaLevel level);
}
