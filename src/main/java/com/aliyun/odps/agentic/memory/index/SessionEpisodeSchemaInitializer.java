package com.aliyun.odps.agentic.memory.index;


import javax.sql.DataSource;

public class SessionEpisodeSchemaInitializer {
    private final DataSource dataSource;

    public SessionEpisodeSchemaInitializer(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public void initializeSchema() {
        try {
            SemanticFactSchemaInitializer.initializeSchema(dataSource, "session_episode", 1,
                "db/migration/V20260524_002__session_episode.sql");
            // §23.12d archive 表 + summary_quality / archived_at 列
            SemanticFactSchemaInitializer.applyVersionedMigration(dataSource, "session_episode", 2,
                "db/migration/V20260524_004__session_episode_archive.sql");
        } catch (Exception e) {
            throw new IllegalStateException("Failed to init session_episode schema", e);
        }
    }
}
