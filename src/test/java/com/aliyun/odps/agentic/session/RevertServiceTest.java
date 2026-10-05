package com.aliyun.odps.agentic.session;

import org.junit.jupiter.api.*;
import com.aliyun.odps.agentic.session.RevertService.*;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for RevertService — faithful to opencode session/revert.ts.
 */
class RevertServiceTest {

    private Path tempDir;

    @BeforeEach
    void setUp() throws Exception {
        tempDir = Files.createTempDirectory("revert-test");
    }

    @AfterEach
    void tearDown() throws IOException {
        Files.walk(tempDir)
            .sorted(Comparator.reverseOrder())
            .forEach(p -> {
                try { Files.deleteIfExists(p); } catch (IOException e) { }
            });
    }

    @Test
    void fileHashStrategyAvailable() {
        FileHashSnapshotStrategy strategy = new FileHashSnapshotStrategy(tempDir);
        assertTrue(strategy.isAvailable());
    }

    @Test
    void fileHashTrackCreatesSnapshot() throws Exception {
        Files.writeString(tempDir.resolve("hello.txt"), "hello world");
        FileHashSnapshotStrategy strategy = new FileHashSnapshotStrategy(tempDir);
        strategy.init();
        Optional<String> snapshot = strategy.track();
        assertTrue(snapshot.isPresent());
        assertFalse(snapshot.get().isEmpty());
    }

    @Test
    void fileHashRestoreRecoversFiles() throws Exception {
        Files.writeString(tempDir.resolve("data.txt"), "original");
        FileHashSnapshotStrategy strategy = new FileHashSnapshotStrategy(tempDir);
        strategy.init();
        Optional<String> snapshot = strategy.track();
        assertTrue(snapshot.isPresent());

        // Modify file
        Files.writeString(tempDir.resolve("data.txt"), "modified");
        assertEquals("modified", Files.readString(tempDir.resolve("data.txt")));

        // Restore
        strategy.restore(snapshot.get());
        assertEquals("original", Files.readString(tempDir.resolve("data.txt")));
    }

    @Test
    void fileHashDiffDetectsChanges() throws Exception {
        Files.writeString(tempDir.resolve("test.txt"), "v1");
        FileHashSnapshotStrategy strategy = new FileHashSnapshotStrategy(tempDir);
        strategy.init();
        Optional<String> snapshot = strategy.track();
        assertTrue(snapshot.isPresent());

        // Modify file
        Files.writeString(tempDir.resolve("test.txt"), "v2");

        String diff = strategy.diff(snapshot.get());
        assertNotNull(diff);
        assertTrue(diff.contains("test.txt"));
    }

    @Test
    void fileHashPatchListsFiles() throws Exception {
        Files.writeString(tempDir.resolve("a.txt"), "aaa");
        Files.writeString(tempDir.resolve("b.txt"), "bbb");
        FileHashSnapshotStrategy strategy = new FileHashSnapshotStrategy(tempDir);
        strategy.init();
        Optional<String> snapshot = strategy.track();
        assertTrue(snapshot.isPresent());

        SnapshotPatch patch = strategy.patch(snapshot.get());
        assertNotNull(patch);
        assertEquals(snapshot.get(), patch.hash());
        assertFalse(patch.files().isEmpty());
    }

    @Test
    void fileHashMultipleSnapshots() throws Exception {
        FileHashSnapshotStrategy strategy = new FileHashSnapshotStrategy(tempDir);
        strategy.init();

        Files.writeString(tempDir.resolve("v1.txt"), "v1");
        Optional<String> snap1 = strategy.track();

        Files.writeString(tempDir.resolve("v2.txt"), "v2");
        Optional<String> snap2 = strategy.track();

        assertNotEquals(snap1.get(), snap2.get());
    }

    @Test
    void fileHashCleanupDoesNotThrow() throws Exception {
        FileHashSnapshotStrategy strategy = new FileHashSnapshotStrategy(tempDir);
        strategy.init();
        strategy.track();
        assertDoesNotThrow(() -> strategy.cleanup());
    }

    @Test
    void diffSummaryCreation() {
        DiffSummary summary = new DiffSummary(3, 2, 1);
        assertEquals(3, summary.additions());
        assertEquals(2, summary.deletions());
        assertEquals(1, summary.files());
    }

    @Test
    void revertInfoCreation() {
        DiffSummary summary = new DiffSummary(1, 0, 1);
        RevertInfo info = new RevertInfo("msg-1", "part-1", "snap-abc", "diff text", summary);
        assertEquals("msg-1", info.messageID());
        assertEquals("part-1", info.partID());
        assertEquals("snap-abc", info.snapshot());
    }

    @Test
    void snapshotPatchRecord() {
        SnapshotPatch patch = new SnapshotPatch("hash123", List.of("file1.txt", "file2.txt"));
        assertEquals("hash123", patch.hash());
        assertEquals(2, patch.files().size());
    }

    @Test
    void revertServiceWithMessageStore() throws Exception {
        Files.writeString(tempDir.resolve("data.txt"), "original");
        FileHashSnapshotStrategy strategy = new FileHashSnapshotStrategy(tempDir);
        strategy.init();
        strategy.track();

        InMemoryMessageStore store = new InMemoryMessageStore();
        RevertService service = new RevertService(strategy, store);

        // Revert with no messages should return null
        assertNull(service.revert("nonexistent-session", "msg-1", null));
    }

    @Test
    void revertServiceGetRevertInfo() throws Exception {
        FileHashSnapshotStrategy strategy = new FileHashSnapshotStrategy(tempDir);
        InMemoryMessageStore store = new InMemoryMessageStore();
        RevertService service = new RevertService(strategy, store);

        assertNull(service.getRevertInfo("nonexistent-session"));
    }

    @Test
    void revertServiceClearRevert() throws Exception {
        FileHashSnapshotStrategy strategy = new FileHashSnapshotStrategy(tempDir);
        InMemoryMessageStore store = new InMemoryMessageStore();
        RevertService service = new RevertService(strategy, store);

        // Should not throw even with no revert info
        assertDoesNotThrow(() -> service.clearRevert("nonexistent-session"));
    }

    @Test
    void revertServiceGetStrategy() {
        FileHashSnapshotStrategy strategy = new FileHashSnapshotStrategy(tempDir);
        InMemoryMessageStore store = new InMemoryMessageStore();
        RevertService service = new RevertService(strategy, store);

        assertSame(strategy, service.getStrategy());
    }
}
