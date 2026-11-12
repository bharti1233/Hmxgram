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
