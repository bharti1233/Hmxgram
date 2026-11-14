package com.feurstagram.extension.ghost;

/**
 * The catalogue of privacy controls.
 *
 * <p>Every entry says what it would do, whether it is supported today, and —
 * when it is not — exactly why. A control that is not supported cannot be
 * switched on: the screen shows the reason instead of a switch that would do
 * nothing.
 *
 * <p>The Instagram-facing controls are all unsupported today. The protected
 * surface policy of this repository forbids global request interception, so a
 * control that would have to change an Instagram request path (a story-seen
 * receipt, a typing indicator, an activity status) is reported as
 * unsupported rather than implemented by changing Instagram's request paths.
 * The two controls that are supported are local: they protect what this app
 * keeps and does on the user's behalf, and they are verified by their own
 * code.
 *
 * <p>Pure-Java: no Android imports.
 */
public final class GhostFeature {

    /** Whether a control can be used today. */
    public enum Support {
        /** The control works and can be switched on. */
        SUPPORTED,
        /** The control cannot be implemented safely; the reason is shown. */
        UNSUPPORTED
    }

    private final String id;
    private final String title;
    private final String explanation;
    private final String unsupportedReason;
    private final String preferenceKey;

    private GhostFeature(String id, String title, String explanation,
            String unsupportedReason, String preferenceKey) {
        this.id = id;
        this.title = title;
        this.explanation = explanation;
        this.unsupportedReason = unsupportedReason;
        this.preferenceKey = preferenceKey;
    }

    private static GhostFeature supported(String id, String title, String explanation) {
        return new GhostFeature(id, title, explanation, null,
                "hmx_ghost_" + id);
    }

    private static GhostFeature unsupported(String id, String title, String explanation,
            String reason) {
        return new GhostFeature(id, title, explanation, reason, "hmx_ghost_" + id);
    }

    // --- the Instagram-facing controls, all unsupported today ---------------------

    /** Hides the story-seen state in the stories tray. */
    public static final GhostFeature HIDE_STORY_SEEN = unsupported(
            "hide_story_seen",
            "Hide story seen",
            "Do not show which stories you have already seen.",
            "The stories tray items are Instagram's own views, and the seen state "
                    + "is written by Instagram when the story is opened. Hiding it "
                    + "would need a hook on Instagram's story items or on its "
                    + "request path, and neither is allowed here.");

    /** Hides the message-seen state in the inbox. */
    public static final GhostFeature HIDE_MESSAGE_SEEN = unsupported(
            "hide_message_seen",
            "Hide message seen",
            "Do not show that your messages were read.",
            "The read receipt is sent by Instagram's messaging code. Suppressing "
                    + "it would mean changing an Instagram request path, which "
                    + "the protected-surface policy forbids.");

    /** Hides the typing indicator. */
    public static final GhostFeature HIDE_TYPING_STATUS = unsupported(
            "hide_typing_status",
            "Hide typing status",
            "Do not tell the other side that you are typing.",
            "The typing indicator is sent over Instagram's messaging connection. "
                    + "Suppressing it would mean changing an Instagram request "
                    + "path, which the protected-surface policy forbids.");

    /** Hides the online status. */
    public static final GhostFeature HIDE_ONLINE_STATUS = unsupported(
            "hide_online_status",
            "Hide online status",
            "Do not appear online.",
            "The presence is published by Instagram's messaging connection. "
                    + "Suppressing it would mean changing an Instagram request "
                    + "path, which the protected-surface policy forbids.");

    /** Disables the activity status. */
    public static final GhostFeature DISABLE_ACTIVITY_STATUS = unsupported(
            "disable_activity_status",
            "Disable activity status",
            "Do not publish that you are active.",
            "The activity status is published by Instagram's messaging "
                    + "connection. Suppressing it would mean changing an "
                    + "Instagram request path, which the protected-surface "
                    + "policy forbids.");

    /** Opens stories without marking them as seen. */
    public static final GhostFeature ANONYMOUS_STORY_VIEWING = unsupported(
            "anonymous_story_viewing",
            "Anonymous story viewing",
            "Open a story without Instagram recording it.",
            "Instagram records the view itself, on its own servers. Nothing this "
                    + "app can do locally changes that, and hiding the local "
                    + "state would only be misleading.");

    /** Hides the live entries in the stories tray. */
    public static final GhostFeature HIDE_LIVE_VIEW = unsupported(
            "hide_live_view",
            "Hide live view",
            "Do not show the live entries in the stories tray.",
            "The live entries are Instagram's own tray items. Hiding them would "
                    + "need a hook on Instagram's story items, which is not "
                    + "allowed here.");

    // --- the local controls, supported today --------------------------------------

    /** Asks before a shared item is saved. */
    public static final GhostFeature CONFIRM_BEFORE_SAVING = supported(
            "confirm_before_saving",
            "Ask before saving",
            "Show a confirmation before a shared photo, video or audio file is "
                    + "saved to the gallery.");

    /** Keeps the download history behind an explicit tap. */
    public static final GhostFeature HIDE_DOWNLOAD_HISTORY = supported(
            "hide_download_history",
            "Hide the download history",
            "Keep the list of downloaded items hidden until you tap to show it.");

    /** The catalogue, in the order the screen lists them. */
    private static final GhostFeature[] ALL = {
            HIDE_STORY_SEEN,
            HIDE_MESSAGE_SEEN,
            HIDE_TYPING_STATUS,
            HIDE_ONLINE_STATUS,
            DISABLE_ACTIVITY_STATUS,
            ANONYMOUS_STORY_VIEWING,
            HIDE_LIVE_VIEW,
            CONFIRM_BEFORE_SAVING,
            HIDE_DOWNLOAD_HISTORY,
    };

    public String id() {
        return id;
    }

    public String title() {
        return title;
    }

    /** What the control would do. */
    public String explanation() {
        return explanation;
    }

    /** Why the control cannot be used, or {@code null} when it is supported. */
    public String unsupportedReason() {
        return unsupportedReason;
    }

    public boolean isSupported() {
        return unsupportedReason == null;
    }

    public Support support() {
        return isSupported() ? Support.SUPPORTED : Support.UNSUPPORTED;
    }

    /** The preference key this control is stored under. */
    public String preferenceKey() {
        return preferenceKey;
    }

    /** Every control of the catalogue. */
    public static GhostFeature[] all() {
        return ALL.clone();
    }

    /**
     * The catalogue with the supported controls first.
     *
     * <p>The screen puts the controls that really switch something at the top,
     * so they are visible without scrolling past the long unsupported
     * explanations. The relative order inside each group is the catalogue
     * order.</p>
     */
    public static GhostFeature[] supportedFirst() {
        GhostFeature[] all = all();
        GhostFeature[] ordered = new GhostFeature[all.length];
        int index = 0;
        for (GhostFeature feature : all) {
            if (feature.isSupported()) {
                ordered[index++] = feature;
            }
        }
        for (GhostFeature feature : all) {
            if (!feature.isSupported()) {
                ordered[index++] = feature;
            }
        }
        return ordered;
    }

    /** The control with this id, or {@code null}. */
    public static GhostFeature byId(String id) {
        if (id == null) {
            return null;
        }
        for (GhostFeature feature : ALL) {
            if (feature.id().equals(id)) {
                return feature;
            }
        }
        return null;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof GhostFeature && ((GhostFeature) other).id.equals(id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return "GhostFeature{" + id + ", " + support() + "}";
    }
}
