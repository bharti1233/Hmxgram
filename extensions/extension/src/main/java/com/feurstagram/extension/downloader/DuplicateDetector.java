package com.feurstagram.extension.downloader;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Remembers what was already downloaded so the same media is not saved twice.
 *
 * Pure Java on purpose: the rules are unit-tested on the JVM without an
 * Android device (scripts/ci/test_downloader_logic.sh).
 */
public final class DuplicateDetector {

    private final Set<String> keys = new LinkedHashSet<>();
    private final int limit;

    public DuplicateDetector() {
        this(500);
    }

    public DuplicateDetector(int limit) {
        this.limit = Math.max(16, limit);
    }

    /**
     * Whether the media was already downloaded.
     *
     * @param mediaId Instagram's media id, may be null
     * @param url     the media URL, may be null
     */
    public boolean isDuplicate(String mediaId, String url) {
        String key = MediaNaming.duplicateKey(mediaId, url);
        return !key.isEmpty() && keys.contains(key);
    }

    /** Whether the media was already downloaded. */
    public boolean isDuplicate(MediaItem item) {
        return item != null && isDuplicate(item.mediaId(), item.url());
    }

    /** Records the media as downloaded. */
    public void markDownloaded(String mediaId, String url) {
        String key = MediaNaming.duplicateKey(mediaId, url);
        if (key.isEmpty()) return;
        if (keys.size() >= limit) {
            // The oldest entry goes first: the history is a convenience, not a
            // ledger, and the gallery itself is the real record.
            String oldest = keys.iterator().next();
            keys.remove(oldest);
        }
        keys.add(key);
    }

    /** Records the media as downloaded. */
    public void markDownloaded(MediaItem item) {
        if (item != null) markDownloaded(item.mediaId(), item.url());
    }

    /** Forgets everything, e.g. after the user clears the history. */
    public void clear() {
        keys.clear();
    }

    public int size() {
        return keys.size();
    }
}
