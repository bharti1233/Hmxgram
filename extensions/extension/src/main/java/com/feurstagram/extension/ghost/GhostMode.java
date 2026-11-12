package com.feurstagram.extension.ghost;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.EnumMap;
import java.util.Map;

/**
 * Ghost mode's own preferences.
 *
 * They live in their own preferences file, separate from the rest of the app's
 * settings, so a control here can never be frozen by, or freeze, the
 * permanent lock that guards the content blocks.
 */
public final class GhostMode {

    private static final String PREFS = "hmx_ghost_mode";

    private static SharedPreferences prefs(Context context) {
        if (context == null) return null;
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Whether the master switch is on. */
    public static boolean isMasterEnabled(Context context) {
        SharedPreferences preferences = prefs(context);
        return preferences != null && preferences.getBoolean(GhostPolicy.MASTER_KEY, false);
    }

    /** Sets the master switch. */
    public static void setMasterEnabled(Context context, boolean enabled) {
        SharedPreferences preferences = prefs(context);
        if (preferences == null) return;
        preferences.edit().putBoolean(GhostPolicy.MASTER_KEY, enabled).apply();
    }

    /** Whether a control's own preference is on. */
    public static boolean isEnabled(Context context, GhostFeature feature) {
        if (feature == null) return false;
        SharedPreferences preferences = prefs(context);
        return preferences != null && preferences.getBoolean(feature.key(), false);
    }

    /** Sets a control's own preference. */
    public static void setEnabled(Context context, GhostFeature feature, boolean enabled) {
        if (feature == null) return;
        SharedPreferences preferences = prefs(context);
        if (preferences == null) return;
        preferences.edit().putBoolean(feature.key(), enabled).apply();
    }

    /** Every control's own preference. */
    public static Map<GhostFeature, Boolean> preferences(Context context) {
        Map<GhostFeature, Boolean> values = new EnumMap<>(GhostFeature.class);
        for (GhostFeature feature : GhostFeature.values()) {
            values.put(feature, isEnabled(context, feature));
        }
        return values;
    }

    /** The controls that are actually in force right now. */
    public static Map<GhostFeature, Boolean> active(Context context) {
        return GhostPolicy.activeControls(isMasterEnabled(context), preferences(context));
    }

    /**
     * Whether a request must be dropped.
     *
     * Called from Instagram's network layer for every outgoing request, so the
     * decision is made once, from the current preferences.
     */
    public static boolean shouldSuppress(Context context, String path) {
        if (context == null || path == null) return false;
        Map<GhostFeature, Boolean> active = active(context);
        return GhostPolicy.shouldSuppress(path,
                Boolean.TRUE.equals(active.get(GhostFeature.HIDE_STORY_SEEN)),
                Boolean.TRUE.equals(active.get(GhostFeature.HIDE_MESSAGE_SEEN)),
                Boolean.TRUE.equals(active.get(GhostFeature.HIDE_TYPING_STATUS)));
    }
}
