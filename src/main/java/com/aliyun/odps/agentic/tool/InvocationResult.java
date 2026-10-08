package com.aliyun.odps.agentic.tool;
import java.util.Map;
public interface InvocationResult {
    boolean isSuccess(); String getMessage(); Map<String,Object> toMap();
}
