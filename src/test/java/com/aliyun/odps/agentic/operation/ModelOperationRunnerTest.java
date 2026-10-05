package com.aliyun.odps.agentic.operation;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ModelOperationRunnerTest {
    @Test
    void defaultObserverRecordsCallsWithoutPerOperationObserver() throws Exception {
        List<ModelOperationRunner.Event> events = new ArrayList<>();
        ModelOperationRunner runner = new ModelOperationRunner(events::add);
        assertEquals("done", runner.run("music", ModelOperationRunner.Modality.AUDIO,
            progress -> "done"));
        assertEquals(List.of(ModelOperationRunner.Phase.STARTED, ModelOperationRunner.Phase.COMPLETED),
            events.stream().map(ModelOperationRunner.Event::phase).toList());
    }

    @Test
    void failedDefaultJournalDoesNotBlockProviderOrPerCallProgress() throws Exception {
        List<ModelOperationRunner.Event> events = new ArrayList<>();
        ModelOperationRunner runner = new ModelOperationRunner(event -> {
            throw new IllegalStateException("journal unavailable");
        });
        assertEquals("done", runner.run("image", ModelOperationRunner.Modality.IMAGE,
            progress -> { progress.accept("downloading"); return "done"; }, events::add));
        assertEquals(List.of(ModelOperationRunner.Phase.STARTED,
            ModelOperationRunner.Phase.PROGRESS, ModelOperationRunner.Phase.COMPLETED),
            events.stream().map(ModelOperationRunner.Event::phase).toList());
    }

    @Test
    void returnedProviderRejectionKeepsResponseAndRecordsFailure() throws Exception {
        List<ModelOperationRunner.Event> events = new ArrayList<>();
        ModelOperationRunner runner = new ModelOperationRunner(events::add);
        Integer status = runner.runClassified("connection.probe", ModelOperationRunner.Modality.TEXT,
            progress -> 401, code -> code == 200);
        assertEquals(401, status);
        assertEquals(List.of(ModelOperationRunner.Phase.STARTED, ModelOperationRunner.Phase.FAILED),
            events.stream().map(ModelOperationRunner.Event::phase).toList());
    }

    @Test
    void typedOperationKeepsResultAndOrderedLifecycle() throws Exception {
        ModelOperationRunner runner = new ModelOperationRunner();
        List<ModelOperationRunner.Event> events = new ArrayList<>();
        Object response = new Object();
        Object returned = runner.run("image-generate", ModelOperationRunner.Modality.IMAGE,
            progress -> { progress.accept("polling"); return response; }, events::add);

        assertSame(response, returned);
        assertEquals(List.of(ModelOperationRunner.Phase.STARTED,
            ModelOperationRunner.Phase.PROGRESS,
            ModelOperationRunner.Phase.COMPLETED),
            events.stream().map(ModelOperationRunner.Event::phase).toList());
        assertEquals(1, events.stream().map(ModelOperationRunner.Event::operationId).distinct().count());
    }

    @Test
    void checkedProviderFailureKeepsTypeAndDoesNotRetry() {
        ModelOperationRunner runner = new ModelOperationRunner();
        List<ModelOperationRunner.Event> events = new ArrayList<>();
        IOException failure = new IOException("network");
        IOException received = assertThrows(IOException.class,
            () -> runner.run("vision", ModelOperationRunner.Modality.VISION,
                progress -> { throw failure; }, events::add));

        assertSame(failure, received);
        assertEquals(List.of(ModelOperationRunner.Phase.STARTED,
            ModelOperationRunner.Phase.FAILED),
            events.stream().map(ModelOperationRunner.Event::phase).toList());
    }

    @Test
    void observerFailureDoesNotChangeTransportOutcome() throws Exception {
        Object response = new Object();
        assertSame(response, new ModelOperationRunner().run("audio",
            ModelOperationRunner.Modality.AUDIO,
            progress -> response, event -> { throw new IllegalStateException("observer"); }));
    }

    @Test
    void realtimeHandleKeepsOneTerminalEventAcrossCloseAndLateCallbacks() {
        List<ModelOperationRunner.Event> events = new ArrayList<>();
        ModelOperationRunner.Handle handle = new ModelOperationRunner().open(
            "voice.realtime", ModelOperationRunner.Modality.AUDIO, events::add);
        handle.progress("session.updated");
        handle.fail(new IOException("upstream closed"));
        handle.complete();
        handle.progress("late chunk");

        assertFalse(handle.isActive());
        assertEquals(List.of(ModelOperationRunner.Phase.STARTED,
            ModelOperationRunner.Phase.PROGRESS,
            ModelOperationRunner.Phase.FAILED),
            events.stream().map(ModelOperationRunner.Event::phase).toList());
    }

    @Test
    void restoredHandleKeepsIdentityAndDoesNotRestartCompletedOperation() {
        List<ModelOperationRunner.Event> events = new ArrayList<>();
        ModelOperationRunner runner = new ModelOperationRunner();
        long started = System.currentTimeMillis() - 100;
        var active = runner.restore("task-1", "video.task", ModelOperationRunner.Modality.VIDEO,
            started, true, events::add);
        active.progress("RUNNING");
        active.complete();
        assertEquals(List.of(ModelOperationRunner.Phase.PROGRESS, ModelOperationRunner.Phase.COMPLETED),
            events.stream().map(ModelOperationRunner.Event::phase).toList());
        assertTrue(events.stream().allMatch(event -> "task-1".equals(event.operationId())));
        assertTrue(events.stream().allMatch(event -> event.elapsedMs() >= 100));

        var terminal = runner.restore("task-1", "video.task", ModelOperationRunner.Modality.VIDEO,
            started, false, events::add);
        terminal.complete();
        terminal.fail(new IOException("late"));
        assertFalse(terminal.isActive());
        assertEquals(2, events.size());
    }
}
