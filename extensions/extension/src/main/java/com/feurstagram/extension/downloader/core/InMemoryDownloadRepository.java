package com.feurstagram.extension.downloader.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Bounded, thread-safe, in-memory {@link DownloadRepository}.
 *
 * <p>This is the reference implementation for unit tests and the fallback for
 * hosts without persistent storage. The default capacity is the documented
 * 100-entry history.
 *
 * <p>Pure-Java core: no Android imports.
 */
public final class InMemoryDownloadRepository implements DownloadRepository {

    public static final int DEFAULT_CAPACITY = 100;

    private final int capacity;
    private final List<DownloadTask> entries = new ArrayList<DownloadTask>();

    public InMemoryDownloadRepository() {
        this(DEFAULT_CAPACITY);
    }

    public InMemoryDownloadRepository(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
    }

    @Override
    public synchronized void save(DownloadTask task) {
        if (task == null) {
            throw new IllegalArgumentException("task must not be null");
        }
        if (!task.state().isTerminal()) {
            throw new IllegalArgumentException(
                    "only finished tasks can be stored, found " + task.state());
        }
        entries.add(0, task);
        while (entries.size() > capacity) {
            entries.remove(entries.size() - 1);
        }
    }

    @Override
    public synchronized List<DownloadTask> history() {
        return Collections.unmodifiableList(new ArrayList<DownloadTask>(entries));
    }

    @Override
    public synchronized List<DownloadTask> history(int limit) {
        if (limit < 0) {
            throw new IllegalArgumentException("limit must not be negative");
        }
        int size = Math.min(limit, entries.size());
        return Collections.unmodifiableList(new ArrayList<DownloadTask>(
                entries.subList(0, size)));
    }

    @Override
    public synchronized boolean contains(String duplicateKey) {
        if (duplicateKey == null || duplicateKey.trim().isEmpty()) {
            throw new IllegalArgumentException("duplicateKey must not be blank");
        }
        for (DownloadTask task : entries) {
            if (task.state() == DownloadState.DONE
                    && task.duplicateKey().equals(duplicateKey)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public synchronized int size() {
        return entries.size();
    }

    public synchronized int capacity() {
        return capacity;
    }

    @Override
    public synchronized void clear() {
        entries.clear();
    }
}
