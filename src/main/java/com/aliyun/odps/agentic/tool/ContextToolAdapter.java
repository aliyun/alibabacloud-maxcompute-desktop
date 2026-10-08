package com.aliyun.odps.agentic.tool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
/** Runs SDK context tools through their registered policy and exposes them as Harness ToolDefs. */
public final class ContextToolAdapter<C,R extends InvocationResult,T extends ContextTool<C,R>> implements ToolDef {
    private final ContextToolRegistry<C,R,T> registry;
    private final String name;
    private final Function<ToolContext,C> context;
    private final ObjectMapper mapper;
    public ContextToolAdapter(ContextToolRegistry<C,R,T> registry,String name,Function<ToolContext,C> context,ObjectMapper mapper) {
        this.registry=registry; this.name=name; this.context=context; this.mapper=mapper;
    }
    private T tool() {
        T tool=registry.getSkill(name);
        if (tool==null) throw new IllegalStateException("Tool no longer registered: "+name);
        return tool;
    }
    public String getId() { return name; }
    public String getDescription() { return tool().getDescription(); }
    public ObjectNode getParametersSchema() {
        var definition=tool().toToolDefinition();
        var function=(Map<?,?>) definition.get("function");
        return mapper.valueToTree(function.get("parameters"));
    }
    public ToolResult execute(JsonNode arguments,ToolContext call) {
        @SuppressWarnings("unchecked") Map<String,Object> args=arguments==null||arguments.isNull()
            ? Map.of() : mapper.convertValue(arguments,Map.class);
        var result=registry.executeSkill(name,args,context.apply(call));
        boolean success=Boolean.TRUE.equals(result.get("success"));
        try { return new ToolResult(name,success ? Map.of() : Map.of("error",true),mapper.writeValueAsString(result),List.of()); }
        catch (Exception e) { throw new IllegalStateException("Tool result serialization failed",e); }
    }
}
