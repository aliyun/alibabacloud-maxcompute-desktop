package com.aliyun.odps.agentic.session;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for RunStateManager — busy/idle/cancel state transitions.
 */
class RunStateManagerTest {

    // -- Initial state --

    @Test
    void initialStateIsNotBusy() {
        RunStateManager mgr = new RunStateManager();
        assertFalse(mgr.isBusy());
    }

    @Test
    void initialStateIsNotCancelled() {
        RunStateManager mgr = new RunStateManager();
        assertFalse(mgr.isCancelRequested());
    }

    // -- setBusy --

    @Test
    void setBusyMakesItBusy() {
        RunStateManager mgr = new RunStateManager();
        mgr.setBusy();
        assertTrue(mgr.isBusy());
    }

    @Test
    void setBusyResetsCancelRequest() {
        RunStateManager mgr = new RunStateManager();
        mgr.requestCancel();
        mgr.setBusy();
        assertFalse(mgr.isCancelRequested());
    }

    // -- setIdle --

    @Test
    void setIdleMakesItNotBusy() {
        RunStateManager mgr = new RunStateManager();
        mgr.setBusy();
        mgr.setIdle();
        assertFalse(mgr.isBusy());
    }

    @Test
    void setIdleResetsCancelRequest() {
        RunStateManager mgr = new RunStateManager();
        mgr.setBusy();
        mgr.requestCancel();
        mgr.setIdle();
        assertFalse(mgr.isCancelRequested());
    }

    // -- requestCancel --

    @Test
    void requestCancelSetsFlag() {
        RunStateManager mgr = new RunStateManager();
        mgr.requestCancel();
        assertTrue(mgr.isCancelRequested());
    }

    // -- Lifecycle sequences --

    @Test
    void lifecycleBusyCancelIdle() {
        RunStateManager mgr = new RunStateManager();
        mgr.setBusy();
        assertTrue(mgr.isBusy());

        mgr.requestCancel();
        assertTrue(mgr.isCancelRequested());
        assertTrue(mgr.isBusy());

        mgr.setIdle();
        assertFalse(mgr.isBusy());
        assertFalse(mgr.isCancelRequested());
    }

    @Test
    void doubleBusyResetsCancelBetween() {
        RunStateManager mgr = new RunStateManager();
        mgr.setBusy();
        mgr.requestCancel();
        assertTrue(mgr.isCancelRequested());

        mgr.setBusy();
        assertFalse(mgr.isCancelRequested(), "Second setBusy should reset cancel");
        assertTrue(mgr.isBusy());
    }
}
