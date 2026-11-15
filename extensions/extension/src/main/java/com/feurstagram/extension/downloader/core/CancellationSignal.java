package com.feurstagram.extension.downloader.core;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Cooperative cancellation flag shared by the queue and one worker.
 *
 * <p>The queue owns the task state; this object only carries the "stop as soon
 * as possible" request across the download, the audio step and the MediaStore
 * publish. Every long-running stage checks it before and during work, so a
 * cancelled task stops writing instead of finishing silently.
 *
 * <p>Pure-Java core: no Android imports.
 */
public final class CancellationSignal {

    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    /** Requests cancellation. Safe to call more than once. */
    public void cancel() {
        cancelled.set(true);
    }

    public boolean isCancelled() {
        return cancelled.get();
    }

    /**
     * Throws when cancellation was requested.
     *
     * @throws DownloadCancelledException when the signal is set
     */
    public void throwIfCancelled() throws DownloadCancelledException {
        if (cancelled.get()) {
            throw new DownloadCancelledException();
        }
    }
}
