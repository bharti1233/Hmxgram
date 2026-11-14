package com.feurstagram.extension.downloader.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The outcome of an explicit download request.
 *
 * <p>An observation is passive. This class is the only bridge from "the user
 * is looking at this" to "the user asked for this": the chip creates it when
 * the user taps Download, and only a {@link Status#READY} request may enqueue
 * anything. The caller then enqueues exactly {@link #targets()}.
 *
 * <p>The rules are pure so they can be tested without Android:
 * <ul>
 *   <li>the master switch off blocks every new request;</li>
 *   <li>a stale observation is refused, so a preloaded row cannot be saved;</li>
 *   <li>a Reel only accepts a playable video, never its cover image;</li>
 *   <li>an expired story is refused;</li>
 *   <li>media without a usable URL is refused instead of being guessed.</li>
 * </ul>
 *
 * <p>Pure-Java core: no Android imports.
 */
public final class ExplicitDownloadRequest {

    /** The result of evaluating a request. */
    public enum Status {
        /** The user may confirm and exactly the targets will be queued. */
        READY,
        /** The downloader master switch is off. */
        DISABLED,
        /** No playable media could be identified on this surface. */
        UNSUPPORTED,
        /** The media is an expired story. */
        EXPIRED
    }

    private final Status status;
    private final List<ObservedMedia> targets;
    private final int skipped;
    private final String message;

    private ExplicitDownloadRequest(Status status, List<ObservedMedia> targets,
            int skipped, String message) {
        this.status = status;
        this.targets = targets;
        this.skipped = skipped;
        this.message = message;
    }

    /**
     * Evaluates an explicit request.
     *
     * @param media     the observed media, or {@code null}
     * @param ageMs     how long ago the observation was accepted
     * @param surface   the surface the user tapped on, or {@code null}
     * @param enabled   the downloader master switch
     * @param nowMs     the current time, for the story expiry rule
     */
    public static ExplicitDownloadRequest evaluate(ObservedMedia media, long ageMs,
            InContextAction.Surface surface, boolean enabled, long nowMs) {
        if (!enabled) {
            return refused(Status.DISABLED, "The HMX downloader is off."
                    + " Turn it on in HMX Insta settings → Downloads.");
        }
        if (media == null || surface == null) {
            return refused(Status.UNSUPPORTED, InContextAction.unavailableReason(
                    media, ageMs, surface));
        }
        if (ageMs < 0L || ageMs > InContextAction.MAX_AGE_MS) {
            return refused(Status.UNSUPPORTED,
                    "The media on screen changed. Open the post again to refresh HMX.");
        }
        if (media.expiresAtEpochMs() > 0L && nowMs >= media.expiresAtEpochMs()) {
            return refused(Status.EXPIRED,
                    "This story has expired and can no longer be saved.");
        }
        List<ObservedMedia> usable = new ArrayList<ObservedMedia>();
        for (ObservedMedia target : InContextAction.targets(media, surface)) {
            if (target.expiresAtEpochMs() > 0L && nowMs >= target.expiresAtEpochMs()) {
                continue;
            }
            usable.add(target);
        }
        if (usable.isEmpty()) {
            return refused(Status.UNSUPPORTED, InContextAction.unavailableReason(
                    media, ageMs, surface));
        }
        return new ExplicitDownloadRequest(Status.READY,
                Collections.unmodifiableList(usable),
                InContextAction.skippedChildren(media), null);
    }

    private static ExplicitDownloadRequest refused(Status status, String message) {
        return new ExplicitDownloadRequest(status,
                Collections.<ObservedMedia>emptyList(), 0, message);
    }

    public Status status() {
        return status;
    }

    /** Whether the user may confirm and queue {@link #targets()}. */
    public boolean isReady() {
        return status == Status.READY && !targets.isEmpty();
    }

    /** Exactly what the confirmation will queue; empty unless ready. */
    public List<ObservedMedia> targets() {
        return targets;
    }

    /** Carousel children that have no usable URL, or {@code 0}. */
    public int skipped() {
        return skipped;
    }

    /** The explanation shown when the request is not ready. */
    public String message() {
        return message;
    }

    @Override
    public String toString() {
        return "ExplicitDownloadRequest{" + status + ", targets=" + targets.size() + '}';
    }
}
