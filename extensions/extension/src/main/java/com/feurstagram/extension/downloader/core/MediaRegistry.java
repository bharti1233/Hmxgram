package com.feurstagram.extension.downloader.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A bounded, thread-safe list of the media the user has been looking at.
 *
 * <p>The registry is the only thing the Instagram hook writes to. It keeps
 * plain copies, never Instagram objects, and it is bounded so that scrolling
 * a long feed cannot grow it without limit.
 *
 * <p>Pure-Java core: no Android imports.
 */
public final class MediaRegistry {

    /** How many observations are kept. */
    public static final int DEFAULT_CAPACITY = 60;

    private final int capacity;
    private final Map<String, ObservedMedia> entries =
            new LinkedHashMap<String, ObservedMedia>();

    public MediaRegistry() {
        this(DEFAULT_CAPACITY);
    }

    public MediaRegistry(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
    }

    /**
     * Records an observation.
     *
     * <p>The same media keeps its place: a repeated observation of the same
     * id moves it back to the front instead of adding a second entry.
     */
    public synchronized void observe(ObservedMedia media) {
        if (media == null) {
            throw new IllegalArgumentException("media must not be null");
        }
        String key = keyOf(media);
        entries.remove(key);
        entries.put(key, media);
        while (entries.size() > capacity) {
            String oldest = entries.keySet().iterator().next();
            entries.remove(oldest);
        }
    }

    /** The observations, most recent first. */
    public synchronized List<ObservedMedia> list() {
        List<ObservedMedia> ordered = new ArrayList<ObservedMedia>(entries.values());
        Collections.reverse(ordered);
        return Collections.unmodifiableList(ordered);
    }

    /** The observation with this id, or {@code null}. */
    public synchronized ObservedMedia find(String id) {
        if (id == null) {
            return null;
        }
        return entries.get(id);
    }

    public synchronized int size() {
        return entries.size();
    }

    public synchronized void clear() {
        entries.clear();
    }

    private static String keyOf(ObservedMedia media) {
        String id = media.id();
        if (id != null && !id.trim().isEmpty()) {
            return id;
        }
        String url = media.url();
        if (url != null && !url.trim().isEmpty()) {
            return "url:" + url;
        }
        // A carousel without an id is keyed by its children.
        StringBuilder key = new StringBuilder("carousel:");
        for (ObservedMedia child : media.children()) {
            key.append(keyOf(child)).append('|');
        }
        return key.toString();
    }
}
