package com.feurstagram.extension.ghost;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Decides what ghost mode actually does, given the master switch and the
 * per-control preferences.
 *
 * The rule is simple and deliberately conservative: the master switch controls
 * only the controls that are independently verified, and a control that
 * Instagram decides on the server stays off no matter what the user picked.
 *
 * Pure Java on purpose: the rules are unit-tested on the JVM without an
 * Android device (scripts/ci/test_ghost_mode.sh).
 */
public final class GhostPolicy {

    private GhostPolicy() {}

    /** The master switch's preference key. */
    public static final String MASTER_KEY = "hmx_ghost_master";

    /**
     * Whether a control is active right now.
     *
     * @param feature the control
     * @param master  whether the master switch is on
     * @param enabled whether the control's own preference is on
     */
    public static boolean isActive(GhostFeature feature, boolean master, boolean enabled) {
        if (feature == null) return false;
        if (!feature.isControllable()) return false;
        if (!master) return false;
        return enabled;
    }

    /** The controls that are active, in declaration order. */
    public static Map<GhostFeature, Boolean> activeControls(boolean master,
                                                              Map<GhostFeature, Boolean> preferences) {
        Map<GhostFeature, Boolean> active = new LinkedHashMap<>();
        for (GhostFeature feature : GhostFeature.values()) {
            boolean enabled = preferences != null && Boolean.TRUE.equals(preferences.get(feature));
            active.put(feature, isActive(feature, master, enabled));
        }
        return active;
    }

    /**
     * The features whose requests are suppressed while they are active.
     *
     * These are the path fragments of the receipts Instagram's network layer
     * sends, matched against the request URI. They were verified to exist in
     * Instagram 454.0.0.54.77:
     *
     * <ul>
     *   <li>{@code /media/{id}/seen/} — the story-view receipt;</li>
     *   <li>{@code /direct_v2/threads/{t}/items/{i}/seen/} — the message read
     *       receipt;</li>
     *   <li>{@code /direct_v2/threads/{t}/items/{i}/activity/} — the typing
     *       indicator.</li>
     * </ul>
     */
    public static final String[] STORY_SEEN_PATHS = {"/media/"};
    public static final String[] STORY_SEEN_SUFFIXES = {"/seen/"};
    public static final String[] MESSAGE_SEEN_MARKERS = {"/direct_v2/", "/items/", "/seen/"};
    public static final String[] TYPING_MARKERS = {"/direct_v2/", "/items/", "/activity/"};

    /**
     * Whether a request must be suppressed.
     *
     * @param path  the request path
     * @param storySeen    the story-seen receipt is suppressed
     * @param messageSeen  the message read receipt is suppressed
     * @param typingStatus the typing indicator is suppressed
     */
    public static boolean shouldSuppress(String path, boolean storySeen, boolean messageSeen,
                                         boolean typingStatus) {
        if (path == null || path.isEmpty()) return false;
        String lower = path.toLowerCase(java.util.Locale.US);

        if (storySeen && isStorySeen(lower)) return true;
        if (messageSeen && containsAll(lower, MESSAGE_SEEN_MARKERS)) return true;
        if (typingStatus && containsAll(lower, TYPING_MARKERS)) return true;
        return false;
    }

    /** Whether the path is the story-view receipt. */
    public static boolean isStorySeen(String lowerPath) {
        if (!containsAll(lowerPath, STORY_SEEN_PATHS)) return false;
        for (String suffix : STORY_SEEN_SUFFIXES) {
            if (lowerPath.endsWith(suffix)) return true;
        }
        return false;
    }

    private static boolean containsAll(String path, String[] markers) {
        for (String marker : markers) {
            if (!path.contains(marker)) return false;
        }
        return true;
    }
}
