package com.feurstagram.extension.downloader.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Resolves the stream URIs of a share intent.
 *
 * <p>Android senders are inconsistent: most put the URI in
 * {@code Intent.EXTRA_STREAM}, some only in {@code Intent.getClipData()}, and a
 * few put a different list in each. The document order of {@code EXTRA_STREAM}
 * wins, then any ClipData item that is not already present is appended, so a
 * duplicate never becomes two downloads.
 *
 * <p>Blank and null entries are ignored; the result is unmodifiable and capped
 * at {@link #MAX_ITEMS}. The Android parser validates the scheme and MIME type
 * of every selected URI afterwards.
 *
 * <p>Pure-Java core: no Android imports, which makes the four share shapes
 * testable without a device.
 */
public final class SharedStreams {

    /** Maximum number of streams accepted from one share intent. */
    public static final int MAX_ITEMS = 10;

    private SharedStreams() {
    }

    /**
     * Merges the two Android stream sources.
     *
     * @param extraStreams URIs from {@code EXTRA_STREAM}, in intent order
     * @param clipDataUris URIs from {@code ClipData}, in item order
     * @return the deduplicated selection, never {@code null}
     * @throws IllegalArgumentException when more than {@link #MAX_ITEMS}
     *         distinct URIs are present
     */
    public static List<String> select(List<String> extraStreams,
            List<String> clipDataUris) {
        Set<String> selected = new LinkedHashSet<String>();
        add(selected, extraStreams);
        add(selected, clipDataUris);
        if (selected.size() > MAX_ITEMS) {
            throw new IllegalArgumentException(
                    "too many shared items: " + selected.size());
        }
        return Collections.unmodifiableList(new ArrayList<String>(selected));
    }

    private static void add(Set<String> selected, List<String> values) {
        if (values == null) {
            return;
        }
        for (String value : values) {
            if (value == null) {
                continue;
            }
            String trimmed = value.trim();
            if (!trimmed.isEmpty()) {
                selected.add(trimmed);
            }
        }
    }
}
