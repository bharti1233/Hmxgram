package com.feurstagram.extension.ghost;

/**
 * The ghost-mode controls and how far each of them can honestly go.
 *
 * The support level is the point of this enum: a control that Instagram
 * decides on the server cannot be faked into working, so it is marked
 * unsupported and the UI says so.
 */
public enum GhostFeature {

    /** Stop sending the story-view receipt. Verified client-side. */
    HIDE_STORY_SEEN(
            "hide_story_seen",
            "Hide Story Seen",
            "Stops the app from sending the story-view receipt. Instagram still sees "
                    + "the media being fetched, so this is not a guarantee of invisibility.",
            Support.SUPPORTED),

    /** Stop sending the message read receipt. Verified client-side. */
    HIDE_MESSAGE_SEEN(
            "hide_message_seen",
            "Hide Message Seen",
            "Stops the app from sending the read receipt for a message you open. "
                    + "The message is still delivered to the server.",
            Support.SUPPORTED),

    /** Stop sending the typing indicator. Verified client-side. */
    HIDE_TYPING_STATUS(
            "hide_typing_status",
            "Hide Typing Status",
            "Stops the app from telling the conversation that you are typing.",
            Support.SUPPORTED),

    /** Instagram's own online-status setting. Server-side. */
    HIDE_ONLINE_STATUS(
            "hide_online_status",
            "Hide Online Status",
            "Instagram decides who may see that you are online. That is a server-side "
                    + "account setting, so this build cannot hide it for you.",
            Support.UNSUPPORTED),

    /** Instagram's own activity-status setting. Server-side. */
    DISABLE_ACTIVITY_STATUS(
            "disable_activity_status",
            "Disable Activity Status",
            "The activity status is an account setting on Instagram's servers. It can be "
                    + "turned off in Instagram's own settings, not from a patch.",
            Support.UNSUPPORTED),

    /** Viewing a story without being recorded. Not possible. */
    ANONYMOUS_STORY_VIEWING(
            "anonymous_story_viewing",
            "Anonymous Story Viewing",
            "A story view is recorded against your account on Instagram's servers. "
                    + "There is no way for the app to watch a story anonymously.",
            Support.UNSUPPORTED),

    /** Hiding that a live was watched. Not possible. */
    HIDE_LIVE_VIEW(
            "hide_live_view",
            "Hide Live View",
            "Whether a live was watched is recorded on Instagram's servers when the "
                    + "stream is fetched. The app cannot hide that.",
            Support.UNSUPPORTED);

    /** How far a control can honestly go. */
    public enum Support {
        /** Implemented and verified against this Instagram build. */
        SUPPORTED,
        /** Implemented, but it cannot be verified end to end without a device. */
        EXPERIMENTAL,
        /** Not possible client-side; the UI shows why. */
        UNSUPPORTED
    }

    private final String key;
    private final String title;
    private final String explanation;
    private final Support support;

    GhostFeature(String key, String title, String explanation, Support support) {
        this.key = key;
        this.title = title;
        this.explanation = explanation;
        this.support = support;
    }

    public String key() {
        return key;
    }

    public String title() {
        return title;
    }

    public String explanation() {
        return explanation;
    }

    public Support support() {
        return support;
    }

    /** Whether the master switch has any say over this control. */
    public boolean isControllable() {
        return support == Support.SUPPORTED || support == Support.EXPERIMENTAL;
    }

    /** The label shown next to the control. */
    public String statusLabel() {
        switch (support) {
            case SUPPORTED:
                return "Supported";
            case EXPERIMENTAL:
                return "Experimental";
            default:
                return "Not supported";
        }
    }
}
