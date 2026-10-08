package com.aliyun.odps.agentic.consolidation;
import com.aliyun.odps.agentic.memory.knowledge.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class StandaloneKnowledgeTest {
    @TempDir Path directory;
    private KnowledgeBaseService service(Path data) {
        var client=new DashScopeRetrievalClient("fixture") {
            public float[][] embed(String model,int dimension,List<String> texts,String type,String instruction) {
                var result=new float[texts.size()][dimension];
                for (int i=0;i<texts.size();i++) {
                    for (String word:texts.get(i).split("\\s+")) result[i][Math.floorMod(word.hashCode(),dimension)]+=1;
                }
                return result;
            }
        };
        var provider=new KnowledgeRetrievalProvider() {
            public Capability capability() { return Capability.ok(); }
            public DashScopeRetrievalClient client() { return client; }
            public String embeddingModel() { return "qwen3.7-text-embedding-flash"; }
            public String rerankModel() { return "qwen3.7-text-rerank"; }
        };
        DocumentTextExtractor extractor=path -> {
            try { return DocumentTextExtractor.ExtractResult.ok(Files.readString(path),List.of()); }
            catch (Exception e) { return DocumentTextExtractor.ExtractResult.failed("error",e.getMessage()); }
        };
        var result=new KnowledgeBaseService(data.toString(),provider,null,extractor,new Chunker(),new KnowledgePrivacyGuard(path -> false));
        result.setMaxDistanceForTest(2); return result;
    }
    private void awaitReady(KnowledgeBaseService service,String id) throws Exception {
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime()<deadline) {
            var base=service.getBase(id);
            if ("READY".equals(base.get("status"))) return;
            if ("FAILED".equals(base.get("status"))) fail("Build failed: "+base.get("lastError"));
            Thread.sleep(25);
        }
        fail("Knowledge build did not finish");
    }
    @Test void nativeVectorIndexPersistsAndIncrementalRefreshSurvivesReopen() throws Exception {
        Path corpus=Files.createDirectory(directory.resolve("corpus"));
        Path data=directory.resolve("data");
        Files.writeString(corpus.resolve("alpha.md"),"# Alpha\n\nalpha migration contract memory persistence");
        String id;
        try (var service=service(data)) {
            id=(String)service.createBase(corpus.toString(),"Fixture",null,1024,Set.of("md"),"off",false).get("kbId");
            awaitReady(service,id);
            var hits=service.search("alpha migration",id,5);
            assertFalse(hits.isEmpty());
            assertTrue(hits.stream().anyMatch(hit -> Boolean.TRUE.equals(hit.get("viaVec"))),"Native vector retrieval must work from SDK resources");
        }
        try (var reopened=service(data)) {
            assertEquals(1,reopened.listDocuments(id).size());
            assertFalse(reopened.search("alpha migration",id,5).isEmpty());
            Files.writeString(corpus.resolve("beta.md"),"# Beta\n\nbeta incremental refresh checkpoint");
            reopened.startBuild(id,false); awaitReady(reopened,id);
            assertEquals(2,reopened.listDocuments(id).size());
            assertFalse(reopened.search("beta incremental",id,5).isEmpty());
            reopened.deleteBase(id); assertTrue(reopened.listBases().isEmpty());
        }
    }
}
