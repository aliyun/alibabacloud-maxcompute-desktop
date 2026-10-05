package com.aliyun.odps.agentic.session;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class SnapshotTrackerTest {

    @TempDir
    Path tempDir;

    @Test
    void emptySnapshotHasNoHashes() {
        SnapshotTracker tracker = new SnapshotTracker(null);
        SnapshotTracker.FileSnapshot snap = tracker.track();
        assertTrue(snap.fileHashes().isEmpty());
    }

    @Test
    void tracksExistingFiles() throws Exception {
        java.nio.file.Files.writeString(tempDir.resolve("hello.txt"), "hello world");
        SnapshotTracker tracker = new SnapshotTracker(tempDir);
        SnapshotTracker.FileSnapshot snap = tracker.track();
        assertEquals(1, snap.fileHashes().size());
        assertTrue(snap.fileHashes().containsKey("hello.txt"));
    }

    @Test
    void diffDetectsCreatedFiles() throws Exception {
        SnapshotTracker tracker = new SnapshotTracker(tempDir);
        SnapshotTracker.FileSnapshot before = tracker.track();

        java.nio.file.Files.writeString(tempDir.resolve("new.txt"), "new file");
        SnapshotTracker.FileSnapshot after = tracker.track();

        SnapshotTracker.SnapshotDiff diff = tracker.diff(before, after);
        assertTrue(diff.created().contains("new.txt"));
        assertTrue(diff.modified().isEmpty());
        assertTrue(diff.deleted().isEmpty());
    }

    @Test
    void diffDetectsModifiedFiles() throws Exception {
        java.nio.file.Files.writeString(tempDir.resolve("mod.txt"), "v1");
        SnapshotTracker tracker = new SnapshotTracker(tempDir);
        SnapshotTracker.FileSnapshot before = tracker.track();

        java.nio.file.Files.writeString(tempDir.resolve("mod.txt"), "v2");
        SnapshotTracker.FileSnapshot after = tracker.track();

        SnapshotTracker.SnapshotDiff diff = tracker.diff(before, after);
        assertTrue(diff.modified().contains("mod.txt"));
        assertTrue(diff.created().isEmpty());
    }

    @Test
    void diffDetectsDeletedFiles() throws Exception {
        java.nio.file.Files.writeString(tempDir.resolve("del.txt"), "will be deleted");
        SnapshotTracker tracker = new SnapshotTracker(tempDir);
        SnapshotTracker.FileSnapshot before = tracker.track();

        java.nio.file.Files.delete(tempDir.resolve("del.txt"));
        SnapshotTracker.FileSnapshot after = tracker.track();

        SnapshotTracker.SnapshotDiff diff = tracker.diff(before, after);
        assertTrue(diff.deleted().contains("del.txt"));
    }

    @Test
    void emptyDiffWhenNoChanges() throws Exception {
        java.nio.file.Files.writeString(tempDir.resolve("same.txt"), "unchanged");
        SnapshotTracker tracker = new SnapshotTracker(tempDir);
        SnapshotTracker.FileSnapshot before = tracker.track();
        SnapshotTracker.FileSnapshot after = tracker.track();

        SnapshotTracker.SnapshotDiff diff = tracker.diff(before, after);
        assertTrue(diff.isEmpty());
    }

    @Test
    void skipsGitAndNodeModules() throws Exception {
        java.nio.file.Files.createDirectories(tempDir.resolve(".git"));
        java.nio.file.Files.createDirectories(tempDir.resolve("node_modules/pkg"));
        java.nio.file.Files.createDirectories(tempDir.resolve("src"));
        java.nio.file.Files.writeString(tempDir.resolve(".git/config"), "git config");
        java.nio.file.Files.writeString(tempDir.resolve("node_modules/pkg/index.js"), "module");
        java.nio.file.Files.writeString(tempDir.resolve("src/Main.java"), "class Main {}");

        SnapshotTracker tracker = new SnapshotTracker(tempDir);
        SnapshotTracker.FileSnapshot snap = tracker.track();

        assertFalse(snap.fileHashes().keySet().stream().anyMatch(p -> p.contains(".git/")));
        assertFalse(snap.fileHashes().keySet().stream().anyMatch(p -> p.contains("node_modules/")));
        assertTrue(snap.fileHashes().containsKey("src/Main.java"));
    }
}
