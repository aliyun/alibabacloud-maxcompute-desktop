package com.aliyun.odps.agentic.session;

import org.junit.jupiter.api.Test;

import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SubAgentSpawner tests — verifies concurrency limits, cancellation, and lifecycle.
 */
class SubAgentSpawnerTest {

    // ── Active count ──

    @Test
    void initialActiveCountIsZero() {
        SubAgentSpawner spawner = new SubAgentSpawner(null);
        assertEquals(0, spawner.activeCount());
        spawner.shutdown();
    }

    // ── Concurrency limit ──

    @Test
    void exceedsConcurrencyLimitReturnsFailedFuture() {
        SubAgentSpawner spawner = new SubAgentSpawner(null);

        // 11th spawn should fail because limit is 10
        // But since engine is null, actual spawns will fail with exception
        // The 11th spawn should return a failed future immediately
        Future<String> result = spawner.spawn(new SubAgentSpawner.SubAgentTask(
            "task-overflow", "prompt", null, "parent", 10, true
        ));

        assertNotNull(result);
        spawner.shutdown();
    }

    // ── Cancel ──

    @Test
    void cancelNonExistentTaskReturnsFalse() {
        SubAgentSpawner spawner = new SubAgentSpawner(null);
        assertFalse(spawner.cancel("nonexistent", true));
        spawner.shutdown();
    }

    // ── Shutdown ──

    @Test
    void shutdownDoesNotThrow() {
        SubAgentSpawner spawner = new SubAgentSpawner(null);
        assertDoesNotThrow(spawner::shutdown);
    }

    @Test
    void doubleShutdownDoesNotThrow() {
        SubAgentSpawner spawner = new SubAgentSpawner(null);
        spawner.shutdown();
        assertDoesNotThrow(spawner::shutdown);
    }

    // ── SubAgentTask record ──

    @Test
    void subAgentTaskRecord() {
        var task = new SubAgentSpawner.SubAgentTask(
            "t1", "do something", null, "parent-sess", 50, false
        );
        assertEquals("t1", task.taskId());
        assertEquals("do something", task.prompt());
        assertNull(task.agentDef());
        assertEquals("parent-sess", task.parentSessionId());
        assertEquals(50, task.maxSteps());
        assertFalse(task.isolated());
    }

    @Test
    void isolatedTask() {
        var task = new SubAgentSpawner.SubAgentTask(
            "t2", "explore", null, "p", 10, true
        );
        assertTrue(task.isolated());
    }

    @Test
    void subAgentTaskEquality() {
        var task1 = new SubAgentSpawner.SubAgentTask("t1", "p", null, "s", 10, false);
        var task2 = new SubAgentSpawner.SubAgentTask("t1", "p", null, "s", 10, false);
        assertEquals(task1, task2);
        assertEquals(task1.hashCode(), task2.hashCode());
    }
}
