package com.aliyun.odps.agentic.consolidation;

import com.aliyun.odps.agentic.config.AgentAdvancedSettings;
import com.aliyun.odps.agentic.memory.index.*;
import com.aliyun.odps.agentic.storage.JdbcSqlDatabase;
import com.aliyun.odps.agentic.skill.manifest.ClasspathManifestResources;
import com.aliyun.odps.agentic.tool.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.jar.*;
import java.net.URLClassLoader;
import static org.junit.jupiter.api.Assertions.*;

/** Runs the extracted kernel with plain JDBC and Java resource loading, without Studio or Spring. */
class StandaloneKernelTest {
    @TempDir Path directory;
    private SQLiteDataSource datasource() {
        var source=new SQLiteDataSource();
        source.setUrl("jdbc:sqlite:"+directory.resolve("memory.db"));
        return source;
    }
    @Test void memoryAndConfigurationSurviveColdReopenWithOwnerIsolation() {
        var datasource=datasource();
        new SemanticFactSchemaInitializer(datasource).initializeSchema();
        var sql=new JdbcSqlDatabase(datasource);
        var store=new SqliteSemanticMemoryStore(sql);
        long now=System.currentTimeMillis();
        store.put(new SemanticFact("preference","Use concise answers","alice",Set.of("style"),now));
        store.put(new SemanticFact("preference","Explain in detail","bob",Set.of("style"),now));
        store.put(new SemanticFact("preference","Use concise Chinese answers","alice",Set.of("style"),now+1));
        assertEquals(1,sql.queryForObject("SELECT count(*) FROM semantic_fact_history",Integer.class));
        var config=new AgentAdvancedSettings(sql); config.init();
        config.saveSqlTimeouts(55,450); config.save(800,250,0.5,180,3,6);
        config.setFastRouteEnabled(false);
        var reopened=new AgentAdvancedSettings(new JdbcSqlDatabase(datasource())); reopened.init();
        assertEquals(800,reopened.getContextLength());
        assertEquals(55,reopened.getSqlSoftTimeout());
        assertEquals(450,reopened.getSqlMaxTimeout());
        assertFalse(reopened.isFastRouteEnabled());
        var coldStore=new SqliteSemanticMemoryStore(new JdbcSqlDatabase(datasource()));
        assertEquals("Use concise Chinese answers",coldStore.get("alice","preference").value());
        assertEquals("Explain in detail",coldStore.get("bob","preference").value());
        assertEquals(now,coldStore.get("alice","preference").createdAt());
        coldStore.delete("alice","custom","preference");
        assertNull(coldStore.get("alice","preference"));
        assertEquals(1,coldStore.sizeFor("bob"));
        new SemanticFactSchemaInitializer(datasource()).initializeSchema();
        assertEquals(1,coldStore.size());
    }
    @Test void episodicIndexUsesPortableArchivePortAndSurvivesColdReopen() {
        var datasource=datasource(); new SessionEpisodeSchemaInitializer(datasource).initializeSchema();
        long now=System.currentTimeMillis();
        Map<String,Object> state=Map.of("id","session-fixture-123","userId","alice","goal","migration memory contract",
            "status","completed","createdAt",now,"updatedAt",now,"lastFinishSummary","migration memory contract verified");
        SessionEpisodeSource source=new SessionEpisodeSource() {
            public Map<String,Object> loadSessionState(String id) { return state; }
            public List<Map<String,Object>> loadSessionMessages(String id) { return List.of(Map.of("role","assistant","content","migration memory contract verified")); }
            public Map<String,Object> loadSessionExecutionView(String id) { return Map.of(); }
            public com.aliyun.odps.agentic.memory.context.MemorySessionSource.SessionView loadSession(String id) { return null; }
            public List<Map<String,Object>> listSessionStates() { return List.of(state); }
        };
        var indexer=new SessionEpisodeIndexer(source);
        try (var episodes=new SqliteEpisodicMemoryService(new JdbcSqlDatabase(datasource),indexer,source)) {
            episodes.indexSession("session-fixture-123"); assertEquals(1,episodes.size());
        }
        try (var cold=new SqliteEpisodicMemoryService(new JdbcSqlDatabase(datasource()),indexer,source)) {
            assertEquals("session-fixture-123",cold.searchByQuery("alice","migration memory",3).get(0).sessionId());
            assertTrue(cold.searchByQuery("bob","migration memory",3).isEmpty());
            cold.deleteSession("session-fixture-123"); assertEquals(0,cold.size());
        }
    }
    @Test void manifestDiscoveryWorksWhenJarHasNoDirectoryEntry() throws Exception {
        Path jar=directory.resolve("skills.jar");
        try (var output=new JarOutputStream(java.nio.file.Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry("META-INF/skills/sample.yaml"));
            output.write("name: sample\ndescription: fixture\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            output.closeEntry();
        }
        var previous=Thread.currentThread().getContextClassLoader();
        try (var loader=new URLClassLoader(new java.net.URL[]{jar.toUri().toURL()},previous)) {
            Thread.currentThread().setContextClassLoader(loader);
            var resources=new ClasspathManifestResources().scan("classpath*:META-INF/skills/*.yaml");
            var resource=resources.stream().filter(r -> r.sourcePath().contains("skills.jar")).findFirst().orElseThrow();
            try (var input=resource.getInputStream()) { assertTrue(new String(input.readAllBytes()).contains("name: sample")); }
        } finally { Thread.currentThread().setContextClassLoader(previous); }
    }
    @Test void toolTimeoutCancelsWorkerAndClosesCapturedExecutionScope() throws Exception {
        var closed=new CountDownLatch(1); var interrupted=new CountDownLatch(1);
        var callerScope=new ThreadLocal<String>(); callerScope.set("action-123");
        var observed=new AtomicBoolean();
        try (var registry=new ContextToolRegistry<String,SkillResult,ContextTool<String,SkillResult>>() {
            protected long executionTimeout(String name,String context,SandboxLevel sandbox) { return 300; }
            protected java.util.function.Supplier<AutoCloseable> captureExecutionScope() {
                String captured=callerScope.get();
                return () -> { callerScope.set(captured); return () -> { callerScope.remove(); closed.countDown(); }; };
            }
        }) {
            registry.register(new ContextTool<>() {
                public String getName() { return "wait"; }
                public String getDescription() { return "Wait until cancelled"; }
                public ParameterSpec[] getParameters() { return new SkillParameter[0]; }
                public SkillResult execute(Map<String,Object> args,String context) {
                    observed.set("action-123".equals(callerScope.get()));
                    try { new CountDownLatch(1).await(); }
                    catch (InterruptedException e) { interrupted.countDown(); Thread.currentThread().interrupt(); }
                    return SkillResult.success("cancelled");
                }
            });
            var result=registry.executeSkill("wait",Map.of(),"host");
            assertEquals(false,result.get("success"));
            assertTrue(interrupted.await(2,TimeUnit.SECONDS));
            assertTrue(closed.await(2,TimeUnit.SECONDS)); assertTrue(observed.get());
            assertEquals("action-123",callerScope.get());
        } finally { callerScope.remove(); }
    }
    @Test void contextToolAdapterPreservesFailureAndRegisteredPolicy() throws Exception {
        var mapper=new ObjectMapper();
        try (var registry=new ContextToolRegistry<String,SkillResult,ContextTool<String,SkillResult>>()) {
            registry.register(new ContextTool<>() {
                public String getName() { return "read"; }
                public String getDescription() { return "Read"; }
                public ParameterSpec[] getParameters() { return new SkillParameter[]{SkillParameter.string("path","Path",true)}; }
                public SkillResult execute(Map<String,Object> args,String context) { return SkillResult.failure("denied in "+context); }
            });
            var adapter=new ContextToolAdapter<>(registry,"read",call -> "scope",mapper);
            assertEquals("path",adapter.getParametersSchema().get("required").get(0).asText());
            assertTrue(adapter.execute(mapper.readTree("{\"path\":\"a\"}"),null).isError());
            assertEquals(1,registry.getExecutionHistory().size());
            registry.unregister("read");
            assertThrows(IllegalStateException.class,adapter::getParametersSchema);
        }
    }
}
