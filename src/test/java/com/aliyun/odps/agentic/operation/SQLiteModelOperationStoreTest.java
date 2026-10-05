package com.aliyun.odps.agentic.operation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SQLiteModelOperationStoreTest {
    @TempDir Path temp;

    @Test
    void recordsLifecycleAndFailureAcrossColdReopenWithoutProviderPayload() throws Exception {
        Path database = temp.resolve("agent-sdk-operations.db");
        String completedId;
        String failedId;
        IOException failure = new IOException("secret provider response");
        try (SQLiteModelOperationStore store = new SQLiteModelOperationStore(database)) {
            ModelOperationRunner runner = new ModelOperationRunner(store);
            runner.run("music.generate", ModelOperationRunner.Modality.AUDIO,
                progress -> { progress.accept("RUNNING"); progress.accept("skSecretToken123"); return "audio"; });
            assertSame(failure, assertThrows(IOException.class,
                () -> runner.run("image.generate", ModelOperationRunner.Modality.IMAGE,
                    progress -> { throw failure; })));
            var latest = store.latest(2);
            failedId = latest.get(0).event().operationId();
            completedId = latest.get(1).event().operationId();
            assertEquals(ModelOperationRunner.Phase.FAILED, latest.get(0).event().phase());
            assertEquals(ModelOperationRunner.Phase.COMPLETED, latest.get(1).event().phase());
        }
        try (SQLiteModelOperationStore reopened = new SQLiteModelOperationStore(database)) {
            var complete = reopened.eventsFor(completedId);
            assertEquals(List.of(ModelOperationRunner.Phase.STARTED,
                    ModelOperationRunner.Phase.PROGRESS, ModelOperationRunner.Phase.PROGRESS,
                    ModelOperationRunner.Phase.COMPLETED),
                complete.stream().map(e -> e.event().phase()).toList());
            assertEquals("RUNNING", complete.get(1).event().detail());
            assertNull(complete.get(2).event().detail());
            assertEquals(List.of(ModelOperationRunner.Phase.STARTED, ModelOperationRunner.Phase.FAILED),
                reopened.eventsFor(failedId).stream().map(e -> e.event().phase()).toList());
            assertNotNull(reopened.eventsFor(failedId).get(1).occurredAtMs());
        }
    }
}
