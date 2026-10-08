package com.aliyun.odps.agentic.tool.schema;

/**
 * Tool schema verbosity level used by ToolDefinitionFactory / ToolActivationService.
 *
 * Higher level means more tokens but richer guidance for the model.
 *
 * <pre>
 *   L0_MINIMAL : name + required param names only (no descriptions)
 *   L1_COMPACT : one-sentence English description + short param descriptions
 *   L2_FULL    : full English description + all param details
 * </pre>
 */
public enum ToolSchemaLevel {
    L0_MINIMAL,
    L1_COMPACT,
    L2_FULL
}
