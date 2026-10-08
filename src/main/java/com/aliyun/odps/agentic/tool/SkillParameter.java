package com.aliyun.odps.agentic.tool;

import java.util.List;
import java.util.Map;

/**
 * Skill 参数定义
 */
public class SkillParameter implements com.aliyun.odps.agentic.tool.ParameterSpec {

    private String name;
    private String type; // "string", "number", "boolean", "array", "object"
    private String description;
    private boolean required;
    private List<String> enumValues;
    private Object defaultValue;
    private Integer maxLength; // §17.2: optional JSON-Schema maxLength constraint (string params only)
    private Map<String, Object> jsonSchema; // object/array 类型的完整 JSON Schema（含 properties/items 等）

    public SkillParameter() {}

    public SkillParameter(String name, String type, String description, boolean required) {
        this.name = name;
        this.type = type;
        this.description = description;
        this.required = required;
    }

    public SkillParameter(String name, String type, String description, boolean required, List<String> enumValues) {
        this(name, type, description, required);
        this.enumValues = enumValues;
    }

    // Getters and Setters
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public boolean isRequired() { return required; }
    public void setRequired(boolean required) { this.required = required; }

    public List<String> getEnumValues() { return enumValues; }
    public void setEnumValues(List<String> enumValues) { this.enumValues = enumValues; }

    public Object getDefaultValue() { return defaultValue; }
    public void setDefaultValue(Object defaultValue) { this.defaultValue = defaultValue; }

    public Integer getMaxLength() { return maxLength; }
    public void setMaxLength(Integer maxLength) { this.maxLength = maxLength; }

    public Map<String, Object> getJsonSchema() { return jsonSchema; }
    public void setJsonSchema(Map<String, Object> jsonSchema) { this.jsonSchema = jsonSchema; }

    // 静态工厂方法
    public static SkillParameter string(String name, String description, boolean required) {
        return new SkillParameter(name, "string", description, required);
    }

    /** §17.2: string param with JSON-schema maxLength constraint (LLM-side hint). */
    public static SkillParameter string(String name, String description, boolean required, int maxLength) {
        SkillParameter p = new SkillParameter(name, "string", description, required);
        p.setMaxLength(maxLength);
        return p;
    }

    public static SkillParameter number(String name, String description, boolean required) {
        return new SkillParameter(name, "number", description, required);
    }

    public static SkillParameter bool(String name, String description, boolean required) {
        return new SkillParameter(name, "boolean", description, required);
    }

    public static SkillParameter enumeration(String name, String description, boolean required, List<String> values) {
        return new SkillParameter(name, "string", description, required, values);
    }
}