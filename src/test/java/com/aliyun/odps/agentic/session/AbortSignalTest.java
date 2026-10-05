package com.aliyun.odps.agentic.session;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AbortSignalTest {

    @Test
    void newSignalIsNotAborted() {
        AbortSignal signal = new AbortSignal();
        assertFalse(signal.isAborted());
        assertNull(signal.getReason());
    }

    @Test
    void abortSetsAborted() {
        AbortSignal signal = new AbortSignal();
        signal.abort("user cancelled");
        assertTrue(signal.isAborted());
        assertEquals("user cancelled", signal.getReason());
    }

    @Test
    void throwIfAbortedThrows() {
        AbortSignal signal = new AbortSignal();
        signal.abort();
        assertThrows(AbortSignal.AbortedException.class, signal::throwIfAborted);
    }

    @Test
    void throwIfAbortedDoesNotThrowWhenNotAborted() {
        AbortSignal signal = new AbortSignal();
        assertDoesNotThrow(signal::throwIfAborted);
    }

    @Test
    void childSeesParentAbort() {
        AbortSignal parent = new AbortSignal();
        AbortSignal child = parent.createChild();

        assertFalse(child.isAborted());
        parent.abort("parent cancelled");
        assertTrue(child.isAborted());
        assertEquals("parent cancelled", child.getReason());
    }

    @Test
    void parentDoesNotSeeChildAbort() {
        AbortSignal parent = new AbortSignal();
        AbortSignal child = parent.createChild();

        child.abort("child cancelled");
        assertFalse(parent.isAborted());
        assertTrue(child.isAborted());
    }

    @Test
    void resetClearsAbort() {
        AbortSignal signal = new AbortSignal();
        signal.abort("test");
        assertTrue(signal.isAborted());

        signal.reset();
        assertFalse(signal.isAborted());
        assertNull(signal.getReason());
    }

    @Test
    void chainedSignals() {
        AbortSignal root = new AbortSignal();
        AbortSignal mid = root.createChild();
        AbortSignal leaf = mid.createChild();

        root.abort("root abort");
        assertTrue(leaf.isAborted());
    }
}
