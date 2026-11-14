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
     * Evaluates a request bound to the exact UI element the user tapped.
     *
     * <p>This is the M11 entry point. The caller hands over the media that was
     * read from the tapped row or Reel page itself; no global "latest"
     * observation and no age window is consulted. That is what makes it
     * impossible for a preloaded Home video or a previous Reel to be saved by
     * accident.
     *
     * @param media     the media bound to the tapped control, or {@code null}
     * @param surface   the surface the control belongs to, or {@code null}
     * @param enabled   the downloader master switch
     * @param nowMs     the current time, for the story expiry rule
     * @param itemIndex the selected carousel child, or {@code -1}
     * @param allItems  whether every carousel child was selected
     */
    public static ExplicitDownloadRequest evaluateBound(ObservedMedia media,
            InContextAction.Surface surface, boolean enabled, long nowMs,
            int itemIndex, boolean allItems) {
        if (!enabled) {
            return refused(Status.DISABLED, "The HMX downloader is off."
                    + " Turn it on in HMX Insta settings → Downloads.");
        }
        if (media == null || surface == null || !hasIdentity(media)) {
            return refused(Status.UNSUPPORTED, UNRESOLVED);
        }
        if (media.expiresAtEpochMs() > 0L && nowMs >= media.expiresAtEpochMs()) {
            return refused(Status.EXPIRED,
                    "This story has expired and can no longer be saved.");
        }
        if (surface == InContextAction.Surface.REELS
                && media.kind() != DownloadKind.VIDEO) {
            return refused(Status.UNSUPPORTED,
                    "This looks like a Reel cover, not a playable video."
                            + " HMX will not download the cover.");
        }
        if (media.kind().isContainer()) {
            List<ObservedMedia> children = media.children();
            if (allItems) {
                return ready(media, InContextAction.targets(media), nowMs);
            }
            if (itemIndex < 0 || itemIndex >= children.size()) {
                return refused(Status.UNSUPPORTED,
                        "Choose the carousel item to download.");
            }
            ObservedMedia child = children.get(itemIndex);
            if (child == null || child.kind().isContainer()
                    || !isBoundTarget(child)) {
                return refused(Status.UNSUPPORTED, UNRESOLVED);
            }
            if (child.expiresAtEpochMs() > 0L && nowMs >= child.expiresAtEpochMs()) {
                return refused(Status.EXPIRED,
                        "This story has expired and can no longer be saved.");
            }
            return new ExplicitDownloadRequest(Status.READY,
                    Collections.singletonList(child), 0, null);
        }
        if (!isBoundTarget(media)) {
            return refused(Status.UNSUPPORTED, UNRESOLVED);
        }
        return ready(media, Collections.singletonList(media), nowMs);
    }

    /** The message used when a control exists but its media cannot be resolved. */
    public static final String UNRESOLVED = "Unable to resolve this media.";

    private static boolean hasIdentity(ObservedMedia media) {
        return media != null && media.id() != null && !media.id().trim().isEmpty();
    }

    private static boolean isBoundTarget(ObservedMedia media) {
        if (!hasIdentity(media) || !media.isDownloadable()) return false;
        try {
            String url = media.url().toLowerCase(java.util.Locale.ROOT);
            if (!url.matches("https?://[a-z0-9.-]+(?::[0-9]+)?/[^\\s\\\\]*")) return false;
            int queryStart = url.indexOf('?');
            int fragmentStart = url.indexOf('#');
            int pathEnd = queryStart < 0 ? url.length() : queryStart;
            if (fragmentStart >= 0) pathEnd = Math.min(pathEnd, fragmentStart);
            String path = url.substring(url.indexOf('/', url.indexOf("://") + 3), pathEnd);
            if (path.endsWith(".m3u8") || path.endsWith(".mpd")
                    || path.endsWith(".m4s") || path.endsWith(".ts")) return false;
            if (media.kind() == DownloadKind.VIDEO
                    && (path.endsWith(".jpg") || path.endsWith(".jpeg")
                    || path.endsWith(".png") || path.endsWith(".webp"))) return false;
            String query = queryStart < 0 ? null : url.substring(queryStart + 1);
            return query == null || !query.toLowerCase(java.util.Locale.ROOT)
                    .matches(".*(?:^|&)(?:bytestart|byteend|range)=.*");
        } catch (Exception ignored) {
            return false;
        }
    }

    /** Whether the bound media is a carousel that needs an item choice. */
    public static boolean needsCarouselChoice(ObservedMedia media) {
        return media != null && media.kind().isContainer()
                && !media.children().isEmpty();
    }

    private static ExplicitDownloadRequest ready(ObservedMedia media,
            List<ObservedMedia> candidates, long nowMs) {
        List<ObservedMedia> usable = new ArrayList<ObservedMedia>();
        for (ObservedMedia target : candidates) {
            if (!isBoundTarget(target)) {
                continue;
            }
            if (target.expiresAtEpochMs() > 0L && nowMs >= target.expiresAtEpochMs()) {
                continue;
            }
            usable.add(target);
        }
        if (usable.isEmpty()) {
            return refused(Status.UNSUPPORTED, UNRESOLVED);
        }
        return new ExplicitDownloadRequest(Status.READY,
                Collections.unmodifiableList(usable),
                InContextAction.skippedChildren(media), null);
    }

    /**
     * Evaluates an unbound, age-based observation.
     *
     * <p>Kept only for the M10 regression suite. M11 production code calls
     * {@link #evaluateBound} with the media bound to the tapped control; the
     * age window is not a substitute for identity and is never used by a
     * control.
     *
     * @param media     the observed media, or {@code null}
     * @param ageMs     how long ago the observation was accepted
     * @param surface   the surface the user tapped on, or {@code null}
     * @param enabled   the downloader master switch
     * @param nowMs     the current time, for the story expiry rule
     */
    @Deprecated
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
