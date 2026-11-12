package com.feurstagram.extension.downloader.core;

/**
 * Decides whether the share target should ask for {@code POST_NOTIFICATIONS}.
 *
 * <p>Android 13 introduced the runtime notification permission; before that the
 * permission does not exist and notifications are always allowed. The app asks
 * once per install: a denial is respected and never re-prompted, and the save
 * still completes. The decision is kept pure so it can be tested for every
 * Android version and permission state without a device.
 *
 * <p>Pure-Java core: no Android imports. The SDK level is passed in.
 */
public final class NotificationPermissionPolicy {

    /** First API level with the runtime notification permission. */
    public static final int ANDROID_13 = 33;

    /** What the share activity should do next. */
    public enum Decision {
        /** Ask the system for POST_NOTIFICATIONS, then continue either way. */
        REQUEST,
        /** Continue immediately; asking again would be noise. */
        SKIP
    }

    private NotificationPermissionPolicy() {
    }

    /**
     * @param sdkInt Android API level
     * @param granted whether POST_NOTIFICATIONS is currently granted
     * @param askedBefore whether this install already showed the prompt once
     */
    public static Decision decide(int sdkInt, boolean granted, boolean askedBefore) {
        if (sdkInt < ANDROID_13) {
            return Decision.SKIP;
        }
        if (granted) {
            return Decision.SKIP;
        }
        if (askedBefore) {
            // One prompt per install. A user who denied it can still enable
            // notifications in the app settings; repeating the dialog on every
            // share would be hostile.
            return Decision.SKIP;
        }
        return Decision.REQUEST;
    }
}
