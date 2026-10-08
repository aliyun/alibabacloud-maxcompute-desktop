package com.aliyun.odps.agentic.memory.index;

import com.aliyun.odps.agentic.memory.index.dto.RankedEpisode;
import com.aliyun.odps.agentic.memory.index.dto.RebuildResult;

import java.util.List;

public interface EpisodicMemoryService {
    List<RankedEpisode> searchByQuery(String userId, String query, int topK);
    void indexSession(String sessionId);
    void deleteSession(String sessionId);
    RebuildResult rebuildIndex();
    default int deleteForUser(String userId) { return 0; }
}
