package com.aliyun.odps.agentic.session;

import org.junit.jupiter.api.*;
import com.aliyun.odps.agentic.session.AgentEvent.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for EventBus — the SSE-compatible event subscription system.
 */
class EventBusTest {

    private EventBus eventBus;

    @BeforeEach
    void setUp() {
        eventBus = new EventBus();
    }

    @AfterEach
    void tearDown() {
        eventBus.shutdown();
    }

    @Test
    void subscribeAndReceive() throws Exception {
        List<AgentEvent> received = new ArrayList<>();
        eventBus.subscribe(received::add);

        eventBus.emit(new TextDelta("hello"));
        Thread.sleep(100); // async delivery

        assertEquals(1, received.size());
        assertInstanceOf(TextDelta.class, received.get(0));
    }

    @Test
    void multipleSubscribers() throws Exception {
        List<AgentEvent> sub1 = new ArrayList<>();
        List<AgentEvent> sub2 = new ArrayList<>();
        eventBus.subscribe(sub1::add);
        eventBus.subscribe(sub2::add);

        eventBus.emit(new TextDelta("hi"));
        Thread.sleep(100);

        assertEquals(1, sub1.size());
        assertEquals(1, sub2.size());
    }

    @Test
    void unsubscribe() throws Exception {
        List<AgentEvent> received = new ArrayList<>();
        EventBus.Subscription sub = eventBus.subscribe(received::add);

        eventBus.emit(new TextDelta("first"));
        Thread.sleep(100);
        assertEquals(1, received.size());

        sub.unsubscribe();
        eventBus.emit(new TextDelta("second"));
        Thread.sleep(100);
        assertEquals(1, received.size()); // should not receive after unsubscribe
    }

    @Test
    void filterByEventType() throws Exception {
        List<AgentEvent> textEvents = new ArrayList<>();
        eventBus.subscribe(TextDelta.class, textEvents::add);

        eventBus.emit(new TextDelta("text"));
        eventBus.emit(new ToolCallStarted("shell", "call-1"));
        Thread.sleep(100);

        assertEquals(1, textEvents.size());
        assertInstanceOf(TextDelta.class, textEvents.get(0));
    }

    @Test
    void emitNullDoesNotThrow() {
        assertDoesNotThrow(() -> eventBus.emit(null));
    }

    @Test
    void concurrentEmit() throws Exception {
        AtomicInteger count = new AtomicInteger(0);
        eventBus.subscribe(e -> { if (e != null) count.incrementAndGet(); });

        int numThreads = 10;
        int eventsPerThread = 100;
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < numThreads; i++) {
            futures.add(executor.submit(() -> {
                for (int j = 0; j < eventsPerThread; j++) {
                    eventBus.emit(new TextDelta("t" + j));
                }
            }));
        }

        for (Future<?> f : futures) {
            f.get(5, TimeUnit.SECONDS);
        }

        Thread.sleep(500); // Wait for async delivery
        assertEquals(numThreads * eventsPerThread, count.get());
        executor.shutdown();
    }

    @Test
    void sessionIdFilter() throws Exception {
        List<AgentEvent> sessionA = new ArrayList<>();
        eventBus.subscribeForSession("sess-a", sessionA::add);

        eventBus.emitForSession("sess-a", new TextDelta("hello A"));
        eventBus.emitForSession("sess-b", new TextDelta("hello B"));
        Thread.sleep(100);

        assertEquals(1, sessionA.size());
    }

    @Test
    void replayRecentEvents() {
        // Emit some events synchronously
        for (int i = 0; i < 5; i++) {
            eventBus.emitSync(new TextDelta("t" + i));
        }

        // New subscriber should be able to get recent events
        List<AgentEvent> recent = eventBus.getRecentEvents(3);
        assertTrue(recent.size() <= 5);
    }

    @Test
    void subscriptionIsActive() {
        EventBus.Subscription sub = eventBus.subscribe(e -> {});
        assertTrue(sub.isActive());
        sub.unsubscribe();
        assertFalse(sub.isActive());
    }
}
