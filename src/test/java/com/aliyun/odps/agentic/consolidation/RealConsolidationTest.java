package com.aliyun.odps.agentic.consolidation;

import com.aliyun.odps.agentic.HarnessEngine;
import com.aliyun.odps.agentic.agent.AgentDefBuilder;
import com.aliyun.odps.agentic.llm.*;
import com.aliyun.odps.agentic.llm.provider.OpenAI;
import com.aliyun.odps.agentic.memory.index.*;
import com.aliyun.odps.agentic.memory.knowledge.*;
import com.aliyun.odps.agentic.operation.ModelOperationRunner;
import com.aliyun.odps.agentic.storage.JdbcSqlDatabase;
import com.aliyun.odps.agentic.tool.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in live acceptance of the standalone memory/tool/knowledge integration. */
@EnabledIfEnvironmentVariable(named="AGENTIC_LIVE_ACCEPTANCE",matches="true")
class RealConsolidationTest {
    @TempDir Path directory;
    @Test @Timeout(60) void harnessInvokesRegisteredToolAndReadsColdPersistentMemory() {
        var datasource=new SQLiteDataSource(); datasource.setUrl("jdbc:sqlite:"+directory.resolve("memory.db"));
        new SemanticFactSchemaInitializer(datasource).initializeSchema();
        var memory=new SqliteSemanticMemoryStore(new JdbcSqlDatabase(datasource));
        String marker="SDK_MEMORY_"+UUID.randomUUID().toString().replace("-","").substring(0,12);
        memory.put(new SemanticFact("verification",marker,"fixture-user",Set.of("acceptance"),System.currentTimeMillis()));
        var coldMemory=new SqliteSemanticMemoryStore(new JdbcSqlDatabase(datasource));
        var called=new AtomicBoolean(); var mapper=new ObjectMapper();
        try (var registry=new ContextToolRegistry<String,SkillResult,ContextTool<String,SkillResult>>()) {
            registry.register(new ContextTool<>() {
                public String getName() { return "recall_verification"; }
                public String getDescription() { return "Read the verification marker from persistent memory. Always use this tool when asked for the marker."; }
                public ParameterSpec[] getParameters() { return new SkillParameter[0]; }
                public SkillResult execute(Map<String,Object> args,String owner) {
                    called.set(true); return SkillResult.success("Verification marker",Map.of("marker",coldMemory.get(owner,"verification").value()));
                }
            });
            var tool=new ContextToolAdapter<>(registry,"recall_verification",call -> "fixture-user",mapper);
            var agent=AgentDefBuilder.create("sdk-memory-acceptance").includeTools("recall_verification")
                .systemPrompt("Use recall_verification to obtain the marker. Return that exact marker, without extra text.").addTool(tool).build();
            var model=OpenAI.configure(null).model(System.getenv().getOrDefault("E2E_TEST_MODEL","qwen3.8-flash"),new ModelLimit(128000,null,2048));
            var engine=HarnessEngine.builder().llmClient(new SseLlmClient()).model(model).build();
            try {
                engine.getToolRegistry().register(tool);
                var result=engine.run(engine.createSession(agent),agent,"Read my verification marker from the memory tool and return it.");
                assertTrue(called.get(),"The live model must call the SDK context registry tool");
                assertTrue(result.getTextContent().contains(marker),"Answer must use the persisted random marker");
                assertEquals(1,registry.getExecutionHistory().size());
            } finally { engine.shutdown(); }
        }
    }
    @Test @Timeout(90) void realEmbeddingRerankAndKnowledgeBuildSurviveColdReopen() throws Exception {
        var events=new ArrayList<ModelOperationRunner.Event>();
        var provider=new ConfiguredRetrievalProvider(() -> new ConfiguredRetrievalProvider.ConnectionSettings(false,
            System.getenv("OPENAI_BASE_URL"),System.getenv("OPENAI_API_KEY")),null);
        provider.setModelOperations(new ModelOperationRunner(events::add));
        assertTrue(provider.capability().available());
        var client=provider.client();
        var vectors=client.embed(provider.embeddingModel(),1024,List.of("SDK memory indexing fixture"),"document",null);
        assertEquals(1024,vectors[0].length);
        var scores=client.rerank(provider.rerankModel(),"agent SDK memory",List.of("An agent SDK persists memory","A recipe for rice"));
        assertTrue(scores[0]>scores[1],"Rerank should prefer the relevant document");
        Path corpus=Files.createDirectory(directory.resolve("corpus"));
        Files.writeString(corpus.resolve("migration.md"),"# Agent SDK\n\nThe agent SDK stores semantic memory in SQLite and manages registered context tools. Omni realtime voice stays in the host.");
        DocumentTextExtractor extractor=path -> {
            try { return DocumentTextExtractor.ExtractResult.ok(Files.readString(path),List.of()); }
            catch (Exception e) { return DocumentTextExtractor.ExtractResult.failed("failed",e.getMessage()); }
        };
        String id; Path data=directory.resolve("knowledge");
        try (var service=new KnowledgeBaseService(data.toString(),provider,null,extractor,new Chunker(),new KnowledgePrivacyGuard(path -> false))) {
            id=(String)service.createBase(corpus.toString(),"LiveAcceptance",null,1024,Set.of("md"),"off",true).get("kbId");
            long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(50);
            while (!"READY".equals(service.getBase(id).get("status")) && System.nanoTime()<deadline) {
                assertNotEquals("FAILED",service.getBase(id).get("status"),"Knowledge build failed"); Thread.sleep(100);
            }
            assertEquals("READY",service.getBase(id).get("status"));
            assertFalse(service.search("Where does the agent SDK store semantic memory?",id,3).isEmpty());
        }
        try (var cold=new KnowledgeBaseService(data.toString(),provider,null,extractor,new Chunker(),new KnowledgePrivacyGuard(path -> false))) {
            assertEquals(1,cold.listDocuments(id).size());
            assertFalse(cold.search("Where does the agent SDK store semantic memory?",id,3).isEmpty());
        }
        assertTrue(events.stream().anyMatch(e -> e.name().equals("retrieval.embedding") && e.phase()==ModelOperationRunner.Phase.COMPLETED));
        assertTrue(events.stream().anyMatch(e -> e.name().equals("retrieval.rerank") && e.phase()==ModelOperationRunner.Phase.COMPLETED));
    }
}
