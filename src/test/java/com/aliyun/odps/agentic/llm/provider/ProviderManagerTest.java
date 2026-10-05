package com.aliyun.odps.agentic.llm.provider;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProviderManagerTest {

    @Test
    void newerModelIsNotRankedBelowHardCodedOlderFamilies() {
        ModelInfo older = ModelCatalog.getModel("openai", "gpt-5").orElseThrow();
        ModelInfo newer = withIdentity(older, "gpt-6-test", "gpt", "2099-01-01");
        var models = new ArrayList<>(List.of(older, newer));

        ProviderManager.sort(models);

        assertEquals("gpt-6-test", models.getFirst().id());
    }

    @Test
    void undatedConfiguredModelIsNotRankedBelowStaleCatalogEntry() {
        ModelInfo older = ModelCatalog.getModel("openai", "gpt-5").orElseThrow();
        ModelInfo configured = withIdentity(older, "new-model-test", "gpt", null);
        var models = new ArrayList<>(List.of(older, configured));

        ProviderManager.sort(models);

        assertEquals("new-model-test", models.getFirst().id());
    }

    @Test
    void smallModelSelectionAcceptsNewFamilyWithoutPriorityListUpdate() {
        ModelInfo baseline = ModelCatalog.getModel("openai", "gpt-5-nano").orElseThrow();
        ModelInfo newer = withIdentity(baseline, "gpt-6-nano-test", "gpt-nano", "2099-01-01");
        ProviderConfig override = new ProviderConfig(
            "openai", "OpenAI", "test", List.of(), "test-key", Map.of(), Map.of(newer.id(), newer)
        );
        ProviderManager manager = ProviderManager.create(new ProviderManager.Config()
            .providerOverrides(Map.of("openai", override)));

        assertEquals("gpt-6-nano-test", manager.getSmallModel("openai").orElseThrow().id());
    }

    private static ModelInfo withIdentity(ModelInfo source, String id, String family, String releaseDate) {
        return new ModelInfo(id, source.providerID(), id, family, source.api(), source.status(),
            source.cost(), source.limit(), source.capabilities(), source.options(), source.headers(),
            releaseDate, source.variants());
    }
}
