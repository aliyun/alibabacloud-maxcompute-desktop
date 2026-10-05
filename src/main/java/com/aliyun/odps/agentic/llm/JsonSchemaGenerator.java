package com.aliyun.odps.agentic.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 从 Java 类型（优先 record / POJO）生成 JSON Schema，用于结构化输出。
 *
 * <p>这是一个刻意的<b>小范围</b>生成器，覆盖结构化输出的常见形状：
 * 字符串、数字、布尔、枚举、嵌套 record/POJO、集合/数组、{@code Map<String, T>}。
 * 不支持的类型退化为不约束的 {@code {}}（由模型自由输出），而不是抛错——
 * 避免因为某个边角类型就让整个结构化输出不可用。
 */
public final class JsonSchemaGenerator {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonSchemaGenerator() {}

    /**
     * 为给定类型生成 JSON Schema（{@code ObjectNode}）。
     *
     * @param type 目标类型
     * @return JSON Schema
     */
    public static ObjectNode schemaFor(Class<?> type) {
        return (ObjectNode) schemaForType(type);
    }

    private static com.fasterxml.jackson.databind.JsonNode schemaForType(Type type) {
        ObjectNode node = MAPPER.createObjectNode();

        if (type instanceof Class<?> cls) {
            // 原始/常见标量
            if (cls == String.class || CharSequence.class.isAssignableFrom(cls)) return node.put("type", "string");
            if (cls == int.class || cls == Integer.class || cls == long.class || cls == Long.class
                || cls == short.class || cls == Short.class || cls == byte.class || cls == Byte.class
                || cls == BigInteger.class) return node.put("type", "integer");
            if (cls == double.class || cls == Double.class || cls == float.class || cls == Float.class
                || cls == BigDecimal.class) return node.put("type", "number");
            if (cls == boolean.class || cls == Boolean.class) return node.put("type", "boolean");
            if (cls.isEnum()) {
                ArrayNode values = MAPPER.createArrayNode();
                for (Object c : cls.getEnumConstants()) values.add(c.toString());
                return node.put("type", "string").set("enum", values);
            }
            if (cls.isArray()) {
                return node.put("type", "array").set("items", schemaForType(cls.getComponentType()));
            }
            if (Collection.class.isAssignableFrom(cls)) {
                // 无法获知元素类型（擦除）时不约束 items
                return node.put("type", "array");
            }
            if (Map.class.isAssignableFrom(cls)) {
                return node.put("type", "object");
            }
            // record / POJO → object
            return objectSchema(cls);
        }

        if (type instanceof ParameterizedType pt && pt.getRawType() instanceof Class<?> raw) {
            if (Collection.class.isAssignableFrom(raw)) {
                Type elem = pt.getActualTypeArguments().length > 0 ? pt.getActualTypeArguments()[0] : Object.class;
                return node.put("type", "array").set("items", schemaForType(elem));
            }
            if (Map.class.isAssignableFrom(raw)) {
                Type value = pt.getActualTypeArguments().length > 1 ? pt.getActualTypeArguments()[1] : Object.class;
                return node.put("type", "object").set("additionalProperties", schemaForType(value));
            }
            return objectSchema(raw);
        }

        return node; // 未知类型：不约束
    }

    /** 为 record / POJO 生成 object schema（字段来自 record components 或 getter 推断）。 */
    private static ObjectNode objectSchema(Class<?> cls) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("type", "object");
        ObjectNode props = MAPPER.createObjectNode();
        ArrayNode required = MAPPER.createArrayNode();

        if (cls.isRecord()) {
            for (RecordComponent rc : cls.getRecordComponents()) {
                props.set(rc.getName(), schemaForType(rc.getGenericType()));
                required.add(rc.getName());
            }
        } else {
            // POJO：用公共字段（简单可靠；getter 推断对命名约定敏感，留给后续增强）
            for (var f : cls.getFields()) {
                props.set(f.getName(), schemaForType(f.getGenericType()));
            }
        }

        if (!props.isEmpty()) node.set("properties", props);
        if (!required.isEmpty()) node.set("required", required);
        return node;
    }
}
