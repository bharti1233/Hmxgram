package com.feurstagram.extension.downloader;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The media Instagram has loaded, captured by the downloader patch.
 *
 * The patch hands every media model the app parses to this registry; the
 * registry keeps the last few, newest first, so the entry points can offer
 * what is on screen. The models are Instagram's own objects: nothing is
 * copied out of them and nothing is sent anywhere.
 *
 * Pure Java on purpose: the rules are unit-tested on the JVM without an
 * Android device (scripts/ci/test_downloader_logic.sh).
 */
public final class MediaRegistry {

    /** How many media are kept. */
    public static final int MAX_ENTRIES = 24;

    private static final MediaRegistry INSTANCE = new MediaRegistry();

    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private final Object lock = new Object();

    private MediaRegistry() {
    }

    public static MediaRegistry get() {
        return INSTANCE;
    }

    /** One captured media model. */
    public static final class Entry {
        private final Object model;
        private final long capturedAt;

        Entry(Object model, long capturedAt) {
            this.model = model;
            this.capturedAt = capturedAt;
        }

        public Object model() {
            return model;
        }

        public long capturedAt() {
            return capturedAt;
        }
    }

    /**
     * Records a media model the app has just parsed or displayed.
     *
     * The model is returned unchanged: the patch that calls this runs right
     * after Instagram's parser produced the model, and the call would
     * otherwise destroy the register that holds it. Returning the model lets
     * the patch put it straight back.
     */
    public Object capture(Object model) {
        if (model == null) return null;
        String id = MediaResolver.mediaId(model);
        String key = id != null ? id : String.valueOf(System.identityHashCode(model));
        synchronized (lock) {
            entries.remove(key);
            entries.put(key, new Entry(model, System.currentTimeMillis()));
            while (entries.size() > MAX_ENTRIES) {
                String oldest = entries.keySet().iterator().next();
                entries.remove(oldest);
            }
        }
        return model;
    }

    /** The captured media, newest first. */
    public List<Entry> entries() {
        List<Entry> out = new ArrayList<>();
        synchronized (lock) {
            List<Entry> all = new ArrayList<>(entries.values());
            all.sort((a, b) -> Long.compare(b.capturedAt, a.capturedAt));
            out.addAll(all);
        }
        return out;
    }

    /** The most recently captured media, or null. */
    public Entry latest() {
        synchronized (lock) {
            Entry newest = null;
            for (Entry entry : entries.values()) {
                if (newest == null || entry.capturedAt > newest.capturedAt) newest = entry;
            }
            return newest;
        }
    }

    /** Forgets every captured media. */
    public void clear() {
        synchronized (lock) {
            entries.clear();
        }
    }
}
