package com.feurstagram.extension.downloader.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The rules behind the in-context download action.
 *
 * <p>The action is an HMX-owned chip drawn over Instagram's window. It is not
 * a row of Instagram's list: the protected row binder is never modified, and
 * no view inside a post is touched. The chip offers the media Instagram
 * itself parsed and {@link MediaModelReader} copied, and only while that
 * observation is fresh, so a stale preloaded row cannot turn into a silent
 * download of the wrong post.
 *
 * <p>The user still confirms every download in {@code InContextDownloadSheet}:
 * the observation carries the media, not a mapping to the row on screen, so
 * the confirmation is what makes the choice explicit.
 *
 * <p>Pure-Java core: no Android imports.
 */
public final class InContextAction {

    /** How long an observation stays offerable after Instagram read it. */
    public static final long MAX_AGE_MS = 20000L;

    /** Observations younger than this are described as "just now". */
    public static final long JUST_NOW_MS = 3000L;

    /** Where the chip was offered. The media rules do not differ per surface. */
    public enum Surface {
        FEED,
        REELS,
        STORIES
    }

    private InContextAction() {
    }

    /**
     * Whether the chip may offer this observation on this surface.
     *
     * @param media   the latest observation, or {@code null}
     * @param ageMs   how long ago Instagram read it, in milliseconds
     * @param surface the surface the chip was drawn on, or {@code null} when
     *                no supported surface could be recognised
     */
    public static boolean canOffer(ObservedMedia media, long ageMs, Surface surface) {
        if (media == null || surface == null) {
            return false;
        }
        if (ageMs < 0L || ageMs > MAX_AGE_MS) {
            return false;
        }
        return !targets(media).isEmpty();
    }

    /**
     * The items that would be queued for this observation.
     *
     * <p>A carousel is expanded into its downloadable children: the engine
     * refuses a container. Children without a usable URL are skipped, never
     * guessed.
     */
    public static List<ObservedMedia> targets(ObservedMedia media) {
        if (media == null) {
            return Collections.emptyList();
        }
        if (!media.kind().isContainer()) {
            return media.isDownloadable()
                    ? Collections.singletonList(media)
                    : Collections.<ObservedMedia>emptyList();
        }
        List<ObservedMedia> targets = new ArrayList<ObservedMedia>();
        for (ObservedMedia child : media.children()) {
            if (child != null && !child.kind().isContainer() && child.isDownloadable()) {
                targets.add(child);
            }
        }
        return Collections.unmodifiableList(targets);
    }

    /** How many carousel children have no usable URL, or {@code 0}. */
    public static int skippedChildren(ObservedMedia media) {
        if (media == null || !media.kind().isContainer()) {
            return 0;
        }
        int skipped = 0;
        for (ObservedMedia child : media.children()) {
            if (child == null || child.kind().isContainer() || !child.isDownloadable()) {
                skipped++;
            }
        }
        return skipped;
    }

    /** The title of the confirmation sheet. */
    public static String title(ObservedMedia media) {
        if (media == null) {
            return "Nothing to download";
        }
        String owner = ownerSuffix(media.owner());
        switch (media.kind()) {
            case PHOTO:
                return "Photo" + owner;
            case VIDEO:
                return (media.hasAudio() ? "Video with audio" : "Video") + owner;
            case CAROUSEL:
                int count = targets(media).size();
                return "Carousel · " + count + (count == 1 ? " item" : " items") + owner;
            default:
                return "Instagram media" + owner;
        }
    }

    /**
     * The explanatory line of the confirmation sheet.
     *
     * @param ageMs   how long ago Instagram read the media
     * @param surface where the chip was drawn
     */
    public static String detail(ObservedMedia media, long ageMs, Surface surface) {
        StringBuilder text = new StringBuilder();
        text.append("Detected ").append(ageLabel(ageMs)).append('.');
        int skipped = skippedChildren(media);
        if (skipped > 0) {
            text.append(' ').append(skipped)
                    .append(skipped == 1 ? " item has" : " items have")
                    .append(" no usable URL and will be skipped.");
        }
        if (surface == Surface.STORIES) {
            text.append(" A story can only be saved while Instagram still has it loaded;"
                    + " private or expired stories cannot be downloaded.");
        }
        text.append(" HMX saves the media Instagram parsed for this screen,"
                + " never a guess from the view layout.");
        return text.toString();
    }

    /** A short, human age such as "just now" or "7s ago". */
    public static String ageLabel(long ageMs) {
        if (ageMs < 0L) {
            return "just now";
        }
        if (ageMs < JUST_NOW_MS) {
            return "just now";
        }
        long seconds = ageMs / 1000L;
        if (seconds < 60L) {
            return seconds + "s ago";
        }
        long minutes = seconds / 60L;
        return minutes + (minutes == 1L ? " minute ago" : " minutes ago");
    }

    /**
     * The message shown after the queue accepted the confirmation.
     *
     * @param added      items the queue accepted
     * @param duplicates items already waiting, running or saved
     * @param failed     items the queue refused (full, or unusable)
     */
    public static String resultMessage(int added, int duplicates, int failed) {
        StringBuilder text = new StringBuilder();
        if (added > 0) {
            text.append("Added ").append(added).append(added == 1 ? " item" : " items")
                    .append(" to the download queue.");
        } else if (duplicates > 0) {
            text.append("Already saved or queued.");
        } else if (failed > 0) {
            text.append("Nothing could be queued.");
        } else {
            text.append("Nothing to download.");
        }
        if (added > 0 && duplicates > 0) {
            text.append(' ').append(duplicates)
                    .append(duplicates == 1 ? " item was" : " items were")
                    .append(" already saved or queued.");
        }
        if (failed > 0 && (added > 0 || duplicates > 0)) {
            text.append(' ').append(failed)
                    .append(failed == 1 ? " item could not" : " items could not")
                    .append(" be queued.");
        }
        return text.toString();
    }

    private static String ownerSuffix(String owner) {
        if (owner == null) {
            return "";
        }
        String trimmed = owner.trim();
        if (trimmed.isEmpty() || "instagram".equalsIgnoreCase(trimmed)) {
            return "";
        }
        return " by " + trimmed;
    }
}
