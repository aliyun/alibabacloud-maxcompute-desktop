package com.aliyun.odps.agentic.tool;
import java.util.List;
public interface ParameterSpec {
    String getName(); String getType(); String getDescription(); boolean isRequired(); List<String> getEnumValues();
    default java.util.Map<String,Object> getJsonSchema() { return null; }
    default Integer getMaxLength() { return null; }
    default Object getDefaultValue() { return null; }
}
