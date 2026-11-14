package com.feurstagram.extension.downloader.android;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * The Downloader ON/OFF switch of the HMX Insta settings.
 *
 * <p>On by default. Off only stops new download actions: the in-context chip,
 * the share target and the retry/download buttons of the downloader screen
 * read this class at the moment they act. The saved history and the files
 * already in the gallery are never touched here.
 *
 * <p>It lives in its own preference file and in the downloader's own package,
 * so the switch cannot disturb any existing Instagram or HMX setting, and the
 * existing settings file stays untouched. A broken preference read falls back
 * to the documented default (on) instead of breaking the caller.
 */
public final class DownloaderSwitch {

    /** The preference file the downloader switch lives in. */
    public static final String PREFS_NAME = "hmx_downloader";

    private static final String KEY_ENABLED = "enabled";

    private DownloaderSwitch() {
    }

    /** Whether new download actions are allowed. Defaults to on. */
    public static boolean isEnabled(Context context) {
        SharedPreferences preferences = preferences(context);
        if (preferences == null) {
            return true;
        }
        try {
            return preferences.getBoolean(KEY_ENABLED, true);
        } catch (RuntimeException e) {
            return true;
        }
    }

    /** Stores the switch. Defaults are kept when the context has no preferences. */
    public static void setEnabled(Context context, boolean value) {
        SharedPreferences preferences = preferences(context);
        if (preferences == null) {
            return;
        }
        try {
            preferences.edit().putBoolean(KEY_ENABLED, value).apply();
        } catch (RuntimeException ignored) {
            // The switch keeps its previous value; nothing better to do.
        }
    }

    private static SharedPreferences preferences(Context context) {
        if (context == null) {
            return null;
        }
        try {
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
