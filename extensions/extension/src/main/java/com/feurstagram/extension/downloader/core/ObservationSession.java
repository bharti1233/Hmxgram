package com.feurstagram.extension.downloader.core;

/**
 * The media the user is looking at right now.
 *
 * <p>This is deliberately tiny and passive: it records observations so the
 * in-context chip can name the media on screen, and it never queues, downloads
 * or asks for a download. Browsing — opening a Reel, scrolling, autoplaying,
 * pausing or replaying — therefore cannot create a job, no matter how many
 * observations Instagram produces.
 *
 * <p>Only one media is current. A repeated observation of the same media
 * refreshes the snapshot and its timestamp instead of adding a second entry,
 * which is what keeps one Reel from becoming several.
 *
 * <p>The session is thread-safe: the observer writes from its worker thread
 * and the chip reads from the main thread.
 *
 * <p>Pure-Java core: no Android imports.
 */
public final class ObservationSession {

    /** What happened to an observation. */
    public enum Outcome {
        /** The observation became the current media. */
        ACCEPTED,
        /** The same media was observed again; its snapshot was refreshed. */
        REFRESHED,
        /** The observation was null and nothing changed. */
        IGNORED
    }

    private ObservedMedia latest;
    private long latestAtMs;
    private long acceptedCount;
    private long refreshCount;

    /**
     * Records what the user is looking at.
     *
     * <p>Nothing is enqueued: this method only updates the session. The caller
     * keeps the explicit confirmation as the only path to a download.
     *
     * @param media the observation, or {@code null}
     * @param nowMs the observation time
     */
    public synchronized Outcome observe(ObservedMedia media, long nowMs) {
        if (media == null) {
            return Outcome.IGNORED;
        }
        if (latest != null && latest.identity().equals(media.identity())) {
            // A Reel or a post can be observed many times: the segments, the
            // cover, the same model parsed again. It is one media, not many.
            latest = media;
            latestAtMs = nowMs;
            refreshCount++;
            return Outcome.REFRESHED;
        }
        latest = media;
        latestAtMs = nowMs;
        acceptedCount++;
        return Outcome.ACCEPTED;
    }

    /** The media on screen, or {@code null} when nothing was observed. */
    public synchronized ObservedMedia latest() {
        return latest;
    }

    /** When the latest observation was accepted, in epoch millis. */
    public synchronized long latestAtMs() {
        return latestAtMs;
    }

    /** How long ago the latest observation was accepted, in milliseconds. */
    public synchronized long latestAgeMs(long nowMs) {
        if (latest == null) {
            return Long.MAX_VALUE;
        }
        return Math.max(0L, nowMs - latestAtMs);
    }

    public synchronized boolean hasObservation() {
        return latest != null;
    }

    /** How many different media became current; repeats are not counted. */
    public synchronized long acceptedCount() {
        return acceptedCount;
    }

    /** How many times the current media was observed again. */
    public synchronized long refreshCount() {
        return refreshCount;
    }

    /** Forgets the current media. */
    public synchronized void clear() {
        latest = null;
        latestAtMs = 0L;
        acceptedCount = 0L;
        refreshCount = 0L;
    }
}
