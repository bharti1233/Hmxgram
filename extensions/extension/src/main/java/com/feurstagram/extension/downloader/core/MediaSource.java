package com.feurstagram.extension.downloader.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The contract between the Android shell and the core: a source exposes the
 * media items Instagram has shown the user, and the core expands carousel
 * containers into individually downloadable items.
 *
 * <p>The core never asks a source to fetch anything. A source only hands over
 * models that Instagram already parsed.
 *
 * <p>Pure-Java core: no Android imports.
 */
public interface MediaSource {

    /** The items exposed by this source, newest first. */
    List<MediaItem> items();

    /** Whether the source has anything to offer. */
    default boolean hasItems() {
        return !items().isEmpty();
    }

    /** Expands carousel containers into their children, preserving order. */
    default List<MediaItem> downloadableItems() {
        return flatten(items());
    }

    /** Expands every carousel in {@code items}, preserving order. */
    static List<MediaItem> flatten(List<MediaItem> items) {
        if (items == null) {
            throw new IllegalArgumentException("items must not be null");
        }
        List<MediaItem> expanded = new ArrayList<MediaItem>(items.size());
        for (MediaItem item : items) {
            if (item == null) {
                throw new IllegalArgumentException("items must not contain null");
            }
            if (item.kind().isContainer()) {
                long containerExpiry = item.expiresAtEpochMs();
                for (MediaItem child : item.children()) {
                    // A carousel cannot outlive its container. Keep the
                    // earliest expiry so an expanded child is still refused
                    // once the container has expired.
                    if (containerExpiry > 0L
                            && (child.expiresAtEpochMs() == 0L
                                    || containerExpiry < child.expiresAtEpochMs())) {
                        expanded.add(child.withExpiresAtEpochMs(containerExpiry));
                    } else {
                        expanded.add(child);
                    }
                }
            } else {
                expanded.add(item);
            }
        }
        return Collections.unmodifiableList(expanded);
    }

    /** A source backed by a fixed list. */
    static MediaSource of(final List<MediaItem> items) {
        if (items == null) {
            throw new IllegalArgumentException("items must not be null");
        }
        final List<MediaItem> copy = Collections.unmodifiableList(
                new ArrayList<MediaItem>(items));
        return () -> copy;
    }
}
