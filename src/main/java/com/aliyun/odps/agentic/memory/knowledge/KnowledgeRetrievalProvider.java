package com.aliyun.odps.agentic.memory.knowledge;
public interface KnowledgeRetrievalProvider {
    record Capability(boolean available,String reason) {
        public static Capability ok() { return new Capability(true,null); }
        public static Capability denied(String reason) { return new Capability(false,reason); }
    }
    Capability capability(); DashScopeRetrievalClient client(); String embeddingModel(); String rerankModel();
}
