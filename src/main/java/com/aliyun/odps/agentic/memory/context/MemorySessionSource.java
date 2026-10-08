package com.aliyun.odps.agentic.memory.context;
import java.util.List;
import java.util.Map;
public interface MemorySessionSource {
    interface Schema { Map<String,String> getColumns(); }
    interface Context {
        Map<String,? extends Schema> getKnownTableSchemas();
        Map<String,List<String>> getKnownPartitionedTables();
        com.aliyun.odps.agentic.memory.index.WorkingMemory<?> getWorkingMemory();
        String getGoal();
    }
    interface SessionView {
        String getId(); String getGoal(); String getStatus();
        long getCreatedAt(); long getUpdatedAt(); Object getResult();
        List<Map<String,Object>> getMessages(); Map<String,Object> getExecutionView();
    }
    Context context(String sessionId);
    SessionView session(String sessionId);
}
