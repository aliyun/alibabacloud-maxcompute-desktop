package com.aliyun.odps.agentic.tool;

import java.util.HashMap;
import java.util.Map;

/**
 * Skill 执行结果
 */
@SuppressWarnings("unchecked")
public class SkillResult implements com.aliyun.odps.agentic.tool.InvocationResult {

    private boolean success;
    private String message;
    private Object data;
    private String error;
    private String outputPath; // 如果生成了文件，记录路径

    public SkillResult() {}

    public static SkillResult success(String message) {
        SkillResult result = new SkillResult();
        result.setSuccess(true);
        result.setMessage(message);
        return result;
    }

    public static SkillResult success(String message, Object data) {
        SkillResult result = success(message);
        result.setData(data);
        return result;
    }

    public static SkillResult success(String message, Object data, String outputPath) {
        SkillResult result = success(message, data);
        result.setOutputPath(outputPath);
        return result;
    }

    public static SkillResult failure(String error) {
        SkillResult result = new SkillResult();
        result.setSuccess(false);
        result.setError(error);
        return result;
    }

    // Getters and Setters
    public boolean isSuccess() { return success; }
    public void setSuccess(boolean success) { this.success = success; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public Object getData() { return data; }
    public void setData(Object data) { this.data = data; }

    public String getError() { return error; }
    public void setError(String error) { this.error = error; }

    public String getOutputPath() { return outputPath; }
    public void setOutputPath(String outputPath) { this.outputPath = outputPath; }

    /**
     * 转换为 Map（用于 JSON 序列化）
     * 如果 data 是 Map，将其字段合并到顶层，避免嵌套
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> toMap() {
        Map<String, Object> map = new HashMap<>();
        map.put("success", success);
        if (message != null) map.put("message", message);
        if (error != null) map.put("error", error);
        if (outputPath != null) map.put("outputPath", outputPath);

        // 如果 data 是 Map，将其字段合并到顶层（避免嵌套）
        if (data instanceof Map) {
            Map<String, Object> dataMap = (Map<String, Object>) data;
            for (Map.Entry<String, Object> entry : dataMap.entrySet()) {
                // 不覆盖已存在的字段
                if (!map.containsKey(entry.getKey())) {
                    map.put(entry.getKey(), entry.getValue());
                }
            }
        } else if (data != null) {
            map.put("data", data);
        }

        return map;
    }
}
