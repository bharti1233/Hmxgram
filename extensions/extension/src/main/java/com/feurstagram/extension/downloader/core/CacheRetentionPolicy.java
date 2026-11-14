package com.feurstagram.extension.downloader.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Bounded cleanup for the private share cache.
 *
 * <p>Failed downloads keep their cached copy so a manual retry does not need
 * the sender's original grant again. Without a bound, those copies would grow
 * without limit. This policy deletes only entries that are
 *
 * <ol>
 *   <li>not referenced by an active or pending task; and</li>
 *   <li>older than the grace period, or needed to keep the cache under the
 *       file-count and byte budgets.</li>
 * </ol>
 *
 * <p>Referenced entries are never returned, even when the cache is over
 * budget: an active download must not lose its source. The caller applies the
 * names oldest-first.
 *
 * <p>Pure-Java core: no Android imports.
 */
public final class CacheRetentionPolicy {

    /** A cached copy is kept at least this long for a manual retry. */
    public static final long DEFAULT_MAX_AGE_MS = 24L * 60L * 60L * 1000L;

    /** Total bytes of unreferenced copies kept after the grace period. */
    public static final long DEFAULT_MAX_BYTES = 512L * 1024L * 1024L;

    /** Maximum number of unreferenced copies kept after the grace period. */
    public static final int DEFAULT_MAX_FILES = 32;

    /** One file in the private share cache. */
    public static final class Entry {

        private final String name;
        private final long bytes;
        private final long lastModifiedMs;
        private final boolean referenced;

        public Entry(String name, long bytes, long lastModifiedMs, boolean referenced) {
            if (name == null || name.trim().isEmpty()) {
                throw new IllegalArgumentException("cache entry needs a name");
            }
            this.name = name;
            this.bytes = Math.max(0L, bytes);
            this.lastModifiedMs = Math.max(0L, lastModifiedMs);
            this.referenced = referenced;
        }

        public String name() {
            return name;
        }

        public long bytes() {
            return bytes;
        }

        public long lastModifiedMs() {
            return lastModifiedMs;
        }

        public boolean referenced() {
            return referenced;
        }
    }

    private static final Comparator<Entry> OLDEST_FIRST = new Comparator<Entry>() {
        @Override
        public int compare(Entry left, Entry right) {
            int byTime = Long.compare(left.lastModifiedMs(), right.lastModifiedMs());
            if (byTime != 0) {
                return byTime;
            }
            return left.name().compareTo(right.name());
        }
    };

    private CacheRetentionPolicy() {
    }

    /** Evicts with the documented defaults. */
    public static List<String> selectEvictions(List<Entry> entries, long nowMs) {
        return selectEvictions(entries, nowMs, DEFAULT_MAX_AGE_MS,
                DEFAULT_MAX_BYTES, DEFAULT_MAX_FILES);
    }

    /**
     * @param entries every file currently in the cache
     * @param nowMs current wall clock
     * @param maxAgeMs grace period before an unreferenced file may be evicted
     * @param maxBytes byte budget for unreferenced files
     * @param maxFiles count budget for unreferenced files
     * @return file names to delete, oldest first, never a referenced entry
     */
    public static List<String> selectEvictions(List<Entry> entries, long nowMs,
            long maxAgeMs, long maxBytes, int maxFiles) {
        if (entries == null) {
            throw new IllegalArgumentException("entries must not be null");
        }
        if (maxAgeMs < 0L || maxBytes < 0L || maxFiles < 0) {
            throw new IllegalArgumentException("cache limits must not be negative");
        }
        List<Entry> candidates = new ArrayList<Entry>();
        Set<Entry> expired = new HashSet<Entry>();
        for (Entry entry : entries) {
            if (entry == null || entry.referenced()) {
                continue;
            }
            candidates.add(entry);
            long age = nowMs - entry.lastModifiedMs();
            if (age > maxAgeMs) {
                expired.add(entry);
            }
        }
        Collections.sort(candidates, OLDEST_FIRST);

        List<String> evicted = new ArrayList<String>();
        long remainingBytes = 0L;
        int remainingFiles = 0;
        for (Entry entry : candidates) {
            if (!expired.contains(entry)) {
                remainingBytes += entry.bytes();
                remainingFiles++;
            }
        }
        for (Entry entry : candidates) {
            boolean overBudget = remainingBytes > maxBytes || remainingFiles > maxFiles;
            if (expired.contains(entry) || overBudget) {
                evicted.add(entry.name());
                if (!expired.contains(entry)) {
                    remainingBytes -= entry.bytes();
                    remainingFiles--;
                }
            }
        }
        return Collections.unmodifiableList(evicted);
    }
}
