package com.feurstagram.extension.downloader.core;

import java.util.Objects;

/**
 * One queued download and its state machine.
 *
 * <p>All transitions are validated: an illegal transition (for example
 * completing a task that was never started) throws
 * {@link IllegalStateException} instead of silently corrupting the queue.
 * The queue owns the task id; tests may supply their own.
 *
 * <p>Pure-Java core: no Android imports.
 */
public final class DownloadTask {

    public static final int DEFAULT_MAX_ATTEMPTS = 3;

    private final String id;
    private final MediaItem item;
    private final int maxAttempts;
    private final long createdAtMs;

    private DownloadState state = DownloadState.QUEUED;
    private int attempts;
    private DownloadError error;
    private DownloadResult result;

    public DownloadTask(String id, MediaItem item) {
        this(id, item, DEFAULT_MAX_ATTEMPTS, System.currentTimeMillis());
    }

    public DownloadTask(String id, MediaItem item, int maxAttempts, long createdAtMs) {
        if (id == null || id.trim().isEmpty()) {
            throw new IllegalArgumentException("task id must not be blank");
        }
        if (item == null) {
            throw new IllegalArgumentException("item must not be null");
        }
        if (maxAttempts <= 0) {
            throw new IllegalArgumentException("maxAttempts must be positive");
        }
        if (createdAtMs < 0L) {
            throw new IllegalArgumentException("createdAtMs must not be negative");
        }
        this.id = id;
        this.item = item;
        this.maxAttempts = maxAttempts;
        this.createdAtMs = createdAtMs;
    }

    /**
     * Rehydrates a terminal task loaded from persisted history.
     *
     * <p>Only {@link DownloadState#isTerminal() terminal} states are accepted:
     * an active task from a previous process must not be resumed silently, so
     * the caller has to start it as a fresh queue entry. The combinations of
     * state, error and result are validated so corrupt history cannot create
     * an impossible task.
     *
     * <p>Package-visible: the Android shell goes through
     * {@link DownloadHistoryCodec}, which is the only supported persister.
     */
    static DownloadTask restore(String id, MediaItem item, int maxAttempts,
            long createdAtMs, DownloadState state, int attempts,
            DownloadError error, DownloadResult result) {
        if (state == null || !state.isTerminal()) {
            throw new IllegalArgumentException(
                    "only terminal tasks can be restored, found " + state);
        }
        if (attempts < 0) {
            throw new IllegalArgumentException("attempts must not be negative");
        }
        if (attempts > maxAttempts) {
            throw new IllegalArgumentException(
                    "attempts (" + attempts + ") exceeds maxAttempts (" + maxAttempts + ")");
        }
        if (state == DownloadState.DONE) {
            if (error != null) {
                throw new IllegalArgumentException("a completed task cannot carry an error");
            }
            if (result == null || !result.isSuccess()) {
                throw new IllegalArgumentException(
                        "a completed task needs a successful result");
            }
        } else {
            if (result != null) {
                throw new IllegalArgumentException(
                        "only a completed task can carry a result");
            }
            if (state == DownloadState.FAILED
                    && (error == null || error == DownloadError.CANCELLED)) {
                throw new IllegalArgumentException(
                        "a failed task needs a non-cancellation error");
            }
            if (state == DownloadState.CANCELLED
                    && error != null && error != DownloadError.CANCELLED) {
                throw new IllegalArgumentException(
                        "a cancelled task can only carry the cancellation error");
            }
        }
        DownloadTask task = new DownloadTask(id, item, maxAttempts, createdAtMs);
        task.state = state;
        task.attempts = attempts;
        task.error = error;
        task.result = result;
        return task;
    }

    /**
     * Copy constructor used by {@link #snapshot()}. The caller must hold this
     * task's monitor so the copied state is consistent.
     */
    private DownloadTask(DownloadTask source) {
        this.id = source.id;
        this.item = source.item;
        this.maxAttempts = source.maxAttempts;
        this.createdAtMs = source.createdAtMs;
        this.state = source.state;
        this.attempts = source.attempts;
        this.error = source.error;
        this.result = source.result;
    }

    public String id() {
        return id;
    }

    public MediaItem item() {
        return item;
    }

    public int maxAttempts() {
        return maxAttempts;
    }

    public long createdAtMs() {
        return createdAtMs;
    }

    public synchronized DownloadState state() {
        return state;
    }

    /** Number of attempts started so far. */
    public synchronized int attempts() {
        return attempts;
    }

    /** The last error, or {@code null}. */
    public synchronized DownloadError error() {
        return error;
    }

    /** The successful result, or {@code null}. */
    public synchronized DownloadResult result() {
        return result;
    }

    public synchronized boolean isTerminal() {
        return state.isTerminal();
    }

    /** QUEUED to RUNNING; increments the attempt counter. */
    public synchronized void start() {
        if (state != DownloadState.QUEUED) {
            throw new IllegalStateException("cannot start a task in state " + state);
        }
        state = DownloadState.RUNNING;
        attempts++;
        error = null;
    }

    /** RUNNING to DONE. */
    public synchronized void succeed(DownloadResult outcome) {
        if (state != DownloadState.RUNNING) {
            throw new IllegalStateException("cannot complete a task in state " + state);
        }
        if (outcome == null || !outcome.isSuccess()) {
            throw new IllegalArgumentException("success needs a successful result");
        }
        result = outcome;
        error = null;
        state = DownloadState.DONE;
    }

    /** RUNNING to FAILED (or CANCELLED when the error is cancellation). */
    public synchronized void fail(DownloadError failure) {
        if (state != DownloadState.RUNNING) {
            throw new IllegalStateException("cannot fail a task in state " + state);
        }
        if (failure == null) {
            throw new IllegalArgumentException("failure must not be null");
        }
        error = failure;
        result = null;
        state = failure == DownloadError.CANCELLED
                ? DownloadState.CANCELLED
                : DownloadState.FAILED;
    }

    /** QUEUED or RUNNING to CANCELLED. */
    public synchronized void cancel() {
        if (!state.isActive()) {
            throw new IllegalStateException("cannot cancel a task in state " + state);
        }
        state = DownloadState.CANCELLED;
        error = DownloadError.CANCELLED;
        result = null;
    }

    /** Whether this failed task may be retried right now. */
    public synchronized boolean canRetry() {
        return state == DownloadState.FAILED
                && error != null
                && error.isRecoverable()
                && attempts < maxAttempts;
    }

    /**
     * FAILED to QUEUED when {@link #canRetry()} is true.
     *
     * @return whether the task was requeued
     */
    public synchronized boolean retry() {
        if (!canRetry()) {
            return false;
        }
        state = DownloadState.QUEUED;
        error = null;
        result = null;
        return true;
    }

    /** The duplicate key of the item. */
    public String duplicateKey() {
        return MediaNaming.duplicateKey(item);
    }

    /**
     * An independent copy of this task at its current state.
     *
     * <p>History stores and returns snapshots so that retrying or inspecting a
     * returned task can never mutate the repository's own entries.
     */
    public synchronized DownloadTask snapshot() {
        return new DownloadTask(this);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof DownloadTask)) {
            return false;
        }
        return id.equals(((DownloadTask) other).id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public synchronized String toString() {
        return "DownloadTask{id=" + id + ", state=" + state + ", attempts=" + attempts
                + ", item=" + item.id() + '}';
    }
}
