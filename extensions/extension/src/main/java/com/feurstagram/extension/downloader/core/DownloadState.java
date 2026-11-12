package com.feurstagram.extension.downloader.core;

/**
 * The lifecycle of one {@link DownloadTask}.
 *
 * <pre>
 *   QUEUED ──start──&gt; RUNNING ──succeed──&gt; DONE
 *     │                  │
 *     │                  ├──fail──&gt; FAILED ──retry──&gt; QUEUED
 *     │                  │
 *     └──────cancel──────┴──────cancel──────&gt; CANCELLED
 * </pre>
 *
 * <p>Pure-Java core: no Android imports.
 */
public enum DownloadState {

    /** Waiting for a worker slot. */
    QUEUED,

    /** Claimed by a worker and in progress. */
    RUNNING,

    /** Finished successfully. */
    DONE,

    /** Finished with an error; may be retried when the error is recoverable. */
    FAILED,

    /** Stopped by the user before completion. */
    CANCELLED;

    /** Whether no further transition is possible without an explicit retry. */
    public boolean isTerminal() {
        return this == DONE || this == FAILED || this == CANCELLED;
    }

    /** Whether the task currently occupies a queue or worker slot. */
    public boolean isActive() {
        return this == QUEUED || this == RUNNING;
    }
}
