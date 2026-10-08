package com.aliyun.odps.agentic.memory.index;
import com.aliyun.odps.agentic.memory.context.MemorySessionSource.SessionView;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
/** Host session persistence and change feed; indexing and ranking are owned by the SDK. */
public interface SessionEpisodeSource {
    enum Type { CREATED, UPDATED, DELETED, DELETE_FAILED }
    record Change(Type type, String sessionId) {}
    Map<String,Object> loadSessionState(String id) throws java.io.IOException;
    List<Map<String,Object>> loadSessionMessages(String id) throws java.io.IOException;
    Map<String,Object> loadSessionExecutionView(String id) throws java.io.IOException;
    SessionView loadSession(String id);
    List<Map<String,Object>> listSessionStates() throws java.io.IOException;
    default Runnable subscribe(Consumer<Change> listener) { return () -> {}; }
}
