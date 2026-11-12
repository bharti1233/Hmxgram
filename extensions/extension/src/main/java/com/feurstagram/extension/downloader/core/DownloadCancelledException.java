package com.feurstagram.extension.downloader.core;

import java.io.IOException;

/**
 * Thrown when a worker observes {@link CancellationSignal#isCancelled()} at a
 * checkpoint. It is an {@link IOException} so the existing storage-error paths
 * stay closed, but it is always mapped to {@link DownloadError#CANCELLED}, never
 * to a storage failure.
 *
 * <p>Pure-Java core: no Android imports.
 */
public final class DownloadCancelledException extends IOException {

    private static final long serialVersionUID = 1L;

    public DownloadCancelledException() {
        super("download cancelled");
    }
}
