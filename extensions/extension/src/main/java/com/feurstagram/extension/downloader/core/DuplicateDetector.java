package com.feurstagram.extension.downloader.core;

import java.util.Iterator;
import java.util.LinkedHashSet;

/**
 * Bounded, insertion-ordered set of duplicate keys.
 *
 * <p>The downloader remembers the key of every <em>successfully saved</em>
 * media item. Remembering only on success means a failed or cancelled
 * download can be attempted again. The oldest key is evicted when the capacity
 * is exceeded, so the store cannot grow without limit.
 *
 * <p>Pure-Java core: no Android imports.
 */
public final class DuplicateDetector {

    public static final int DEFAULT_CAPACITY = 512;

    private final int capacity;
    private final LinkedHashSet<String> keys = new LinkedHashSet<String>();

    public DuplicateDetector() {
        this(DEFAULT_CAPACITY);
    }

    public DuplicateDetector(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
    }

    public synchronized boolean isSeen(String key) {
        requireKey(key);
        return keys.contains(key);
    }

    /**
     * Adds {@code key} if absent, evicting the oldest key if needed.
     *
     * @return {@code true} when the key was new
     */
    public synchronized boolean remember(String key) {
        requireKey(key);
        if (keys.contains(key)) {
            return false;
        }
        keys.add(key);
        while (keys.size() > capacity) {
            Iterator<String> oldest = keys.iterator();
            oldest.next();
            oldest.remove();
        }
        return true;
    }

    public synchronized boolean isSeen(MediaItem item) {
        return isSeen(MediaNaming.duplicateKey(item));
    }

    public synchronized boolean remember(MediaItem item) {
        return remember(MediaNaming.duplicateKey(item));
    }

    public synchronized int size() {
        return keys.size();
    }

    public synchronized int capacity() {
        return capacity;
    }

    public synchronized void clear() {
        keys.clear();
    }

    private static void requireKey(String key) {
        if (key == null || key.trim().isEmpty()) {
            throw new IllegalArgumentException("duplicate key must not be blank");
        }
    }
}
