package com.feurstagram.extension.downloader.core;

import java.util.List;

/**
 * Where finished downloads are remembered.
 *
 * <p>The core only defines the contract. The in-memory implementation is used
 * by unit tests; the Android shell will back the same interface with the
 * app's own preferences file ({@code hmx_download_history}), never with a
 * network service.
 *
 * <p>Pure-Java core: no Android imports.
 */
public interface DownloadRepository {

    /**
     * Stores a terminal task, newest first.
     *
     * @throws IllegalArgumentException when the task is still active
     */
    void save(DownloadTask task);

    /** All remembered tasks, newest first. */
    List<DownloadTask> history();

    /** At most {@code limit} remembered tasks, newest first. */
    List<DownloadTask> history(int limit);

    /** Whether a successfully completed task with this duplicate key exists. */
    boolean contains(String duplicateKey);

    /** Number of remembered entries. */
    int size();

    /** Removes every entry. */
    void clear();
}
