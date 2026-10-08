package com.aliyun.odps.agentic.tool;
import java.util.Map;
/** Context-typed host tools share SDK registration/execution without leaking business context into the SDK. */
public interface ContextTool<C,R extends InvocationResult> {
    String getName(); String getDescription(); ParameterSpec[] getParameters();
    R execute(Map<String,Object> args,C context);
    default Map<String, Object> toToolDefinition() {
        // LinkedHashMap: 保证 Jackson 序列化时字段顺序确定，DashScope 显式缓存
        // 要求 tool 数组每次 JSON 字节级一致才能命中（HashMap 顺序在 JVM 间不稳定）。
        Map<String, Object> tool = new java.util.LinkedHashMap<>();
        tool.put("type", "function");

        Map<String, Object> function = new java.util.LinkedHashMap<>();
        function.put("name", getName());
        function.put("description", getDescription());

        // 构建 parameters schema：固定顺序 type → properties → required
        Map<String, Object> parameters = new java.util.LinkedHashMap<>();
        parameters.put("type", "object");

        // properties：按 SkillParameter 声明顺序插入
        Map<String, Object> properties = new java.util.LinkedHashMap<>();
        java.util.List<String> required = new java.util.ArrayList<>();

        for (ParameterSpec param : getParameters()) {
            // 单个 prop：固定顺序 type → description → enum
            Map<String, Object> prop = new java.util.LinkedHashMap<>();
            prop.put("type", param.getType());
            prop.put("description", param.getDescription());
            if (param.getEnumValues() != null && !param.getEnumValues().isEmpty()) {
                prop.put("enum", param.getEnumValues());
            }
            properties.put(param.getName(), prop);

            if (param.isRequired()) {
                required.add(param.getName());
            }
        }

        parameters.put("properties", properties);
        if (!required.isEmpty()) {
            parameters.put("required", required);
        }

        function.put("parameters", parameters);
        tool.put("function", function);

        return tool;
    }
}
