package com.aliyun.odps.agentic.operation;

import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SDK lifecycle for typed model operations that are not conversational agent turns.
 * The host supplies its transport and retains the typed response, including media
 * tasks and provider-specific metadata. Events deliberately contain no prompts,
 * credentials, request bodies or response payloads.
 */
public final class ModelOperationRunner {
    private static final Logger log = LoggerFactory.getLogger(ModelOperationRunner.class);
    public enum Modality { TEXT, VISION, IMAGE, VIDEO, AUDIO, WORLD }
    public enum Phase { STARTED, PROGRESS, COMPLETED, FAILED }

    public record Event(String operationId, String name, Modality modality,
                        Phase phase, long elapsedMs, String detail) {}

    @FunctionalInterface
    public interface Operation<T, E extends Exception> {
        T execute(Consumer<String> progress) throws E;
    }

    private final Clock clock;
    private final Consumer<Event> defaultObserver;

    public ModelOperationRunner() {
        this(Clock.systemUTC(), null);
    }

    /** Observe every operation, including calls without a per-operation observer. */
    public ModelOperationRunner(Consumer<Event> defaultObserver) {
        this(Clock.systemUTC(), defaultObserver);
    }

    ModelOperationRunner(Clock clock) {
        this(clock, null);
    }

    ModelOperationRunner(Clock clock, Consumer<Event> defaultObserver) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.defaultObserver = defaultObserver;
    }

    /** Lifecycle handle for a long-lived model stream such as a realtime voice session. */
    public final class Handle implements AutoCloseable {
        private final String id;
        private final String name;
        private final Modality modality;
        private final Consumer<Event> observer;
        private final long started;
        private final AtomicBoolean finished;

        private Handle(String name, Modality modality, Consumer<Event> observer) {
            this(UUID.randomUUID().toString(), name, modality, observer, clock.millis(), true);
            emit(Phase.STARTED, null);
        }

        private Handle(String id, String name, Modality modality, Consumer<Event> observer,
                       long started, boolean active) {
            this.id = id;
            this.name = name;
            this.modality = modality;
            this.observer = observer;
            this.started = started;
            this.finished = new AtomicBoolean(!active);
        }

        public String operationId() { return id; }
        public boolean isActive() { return !finished.get(); }

        public void progress(String detail) {
            if (isActive()) emit(Phase.PROGRESS, detail);
        }

        public void complete() {
            if (finished.compareAndSet(false, true)) emit(Phase.COMPLETED, null);
        }

        public void fail(Throwable failure) {
            if (finished.compareAndSet(false, true)) emit(Phase.FAILED,
                failure == null ? null : failure.getClass().getSimpleName());
        }

    @Override public void close() { complete(); }

        private void emit(Phase phase, String detail) {
            Event event = new Event(id, name, modality, phase,
                Math.max(0, clock.millis() - started), detail);
            publish(defaultObserver, event);
            if (observer != defaultObserver) publish(observer, event);
        }
    }

    public Handle open(String name, Modality modality, Consumer<Event> observer) {
        return new Handle(Objects.requireNonNull(name, "name"),
            Objects.requireNonNull(modality, "modality"), observer);
    }

    /** Reattach a persisted operation without emitting a second STARTED event. */
    public Handle restore(String operationId, String name, Modality modality,
                          long startedAtMs, boolean active, Consumer<Event> observer) {
        if (operationId == null || operationId.isBlank()) {
            throw new IllegalArgumentException("operationId is required");
        }
        if (startedAtMs < 0) throw new IllegalArgumentException("startedAtMs must be nonnegative");
        return new Handle(operationId, Objects.requireNonNull(name, "name"),
            Objects.requireNonNull(modality, "modality"), observer, startedAtMs, active);
    }

    /** Executes exactly once; the caller retains its existing retry and error policy. */
    public <T, E extends Exception> T run(String name, Modality modality,
                                           Operation<T, E> operation,
                                           Consumer<Event> observer) throws E {
        return runClassified(name, modality, operation, ignored -> true, observer);
    }

    /** Preserve a host's typed response while recording a returned failure as FAILED. */
    public <T, E extends Exception> T runClassified(String name, Modality modality,
            Operation<T, E> operation, Predicate<? super T> succeeded,
            Consumer<Event> observer) throws E {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(modality, "modality");
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(succeeded, "succeeded");
        Handle handle = open(name, modality, observer);
        try {
            T result = operation.execute(handle::progress);
            if (succeeded.test(result)) handle.complete();
            else handle.fail(null);
            return result;
        } catch (RuntimeException | Error failure) {
            handle.fail(failure);
            throw failure;
        } catch (Exception failure) {
            handle.fail(failure);
            throw failure;
        }
    }

    public <T, E extends Exception> T run(String name, Modality modality,
                                           Operation<T, E> operation) throws E {
        return run(name, modality, operation, null);
    }

    public <T, E extends Exception> T runClassified(String name, Modality modality,
            Operation<T, E> operation, Predicate<? super T> succeeded) throws E {
        return runClassified(name, modality, operation, succeeded, null);
    }

    private static void publish(Consumer<Event> observer, Event event) {
        if (observer == null) return;
        try {
            observer.accept(event);
        } catch (RuntimeException failure) {
            // A failed diagnostic observer must not change the model operation.
            log.warn("Model operation observer failed: {}", failure.getClass().getSimpleName());
        }
    }
}
