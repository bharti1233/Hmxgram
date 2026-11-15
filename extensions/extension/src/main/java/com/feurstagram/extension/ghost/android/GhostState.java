package com.feurstagram.extension.ghost.android;

import com.feurstagram.extension.ghost.GhostFeature;
import com.feurstagram.extension.ghost.GhostPolicy;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * The privacy preferences, in their own file.
 *
 * <p>The state is independent of the downloader's preferences and of every
 * other setting of the app, so switching a privacy control off can never
 * disturb the downloader and the other way round.
 *
 * <p>The state is cached in memory so that reading it costs nothing: the
 * controls are read on the paths that draw the UI, and a preference read on
 * every frame would be wasteful.
 *
 * <p>Every control starts off, including the master switch.
 */
public final class GhostState {

    /** The preference file the privacy state lives in. */
    public static final String PREFS_NAME = "hmx_ghost_mode";

    private static final String KEY_MASTER = "master";

    private static volatile GhostState instance;

    private final SharedPreferences preferences;
    private volatile boolean master;
    private volatile boolean[] features;

    private GhostState(Context context) {
        this.preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        this.master = preferences.getBoolean(KEY_MASTER, false);
        GhostFeature[] all = GhostFeature.all();
        this.features = new boolean[all.length];
        for (int index = 0; index < all.length; index++) {
            features[index] = preferences.getBoolean(
                    all[index].preferenceKey(), false);
        }
    }

    /** The process-wide state. */
    public static GhostState get(Context context) {
        if (context == null) {
            throw new IllegalArgumentException("context must not be null");
        }
        GhostState current = instance;
        if (current == null) {
            synchronized (GhostState.class) {
                current = instance;
                if (current == null) {
                    current = new GhostState(context.getApplicationContext());
                    instance = current;
                }
            }
        }
        return current;
    }

    /** Whether the master switch is on. */
    public boolean isMasterOn() {
        return master;
    }

    /** Turns the master switch on or off. */
    public void setMasterOn(boolean value) {
        master = value;
        preferences.edit().putBoolean(KEY_MASTER, value).commit();
    }

    /** Whether the control is in effect: supported, its own switch on and the
     * master switch on.
     *
     * <p>This is the safe way to ask: a failure inside the privacy state must
     * never reach the caller, so a broken preference file cannot break the
     * downloader or the UI that asked.
     */
    public static boolean isActiveSafely(Context context, GhostFeature feature) {
        try {
            return get(context).isActive(feature);
        } catch (RuntimeException e) {
            // Fail safe: a privacy control that cannot be read is off.
            return false;
        }
    }

    /** Whether the control's own switch is on, whatever the master switch says. */
    public boolean isFeatureOn(GhostFeature feature) {
        if (feature == null) {
            throw new IllegalArgumentException("feature must not be null");
        }
        int index = indexOf(feature);
        return index >= 0 && features[index];
    }

    /**
     * Whether the control is in effect: supported, its own switch on and the
     * master switch on.
     */
    public boolean isActive(GhostFeature feature) {
        return GhostPolicy.isActive(feature, master, isFeatureOn(feature));
    }

    /**
     * Switches a control on or off.
     *
     * <p>An unsupported control is refused: its stored value stays off, so a
     * control that does nothing can never claim to be on.
     *
     * @return whether the value was stored
     */
    public boolean setFeatureOn(GhostFeature feature, boolean value) {
        if (feature == null) {
            throw new IllegalArgumentException("feature must not be null");
        }
        if (!GhostPolicy.mayEnable(feature)) {
            return false;
        }
        int index = indexOf(feature);
        if (index < 0) {
            return false;
        }
        features[index] = value;
        preferences.edit().putBoolean(feature.preferenceKey(), value).commit();
        return true;
    }

    /** Forgets every privacy preference. */
    public void clear() {
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean(KEY_MASTER, false);
        for (GhostFeature feature : GhostFeature.all()) {
            editor.putBoolean(feature.preferenceKey(), false);
        }
        editor.commit();
        master = false;
        for (int index = 0; index < features.length; index++) {
            features[index] = false;
        }
    }

    private int indexOf(GhostFeature feature) {
        GhostFeature[] all = GhostFeature.all();
        for (int index = 0; index < all.length; index++) {
            if (all[index].id().equals(feature.id())) {
                return index;
            }
        }
        return -1;
    }
}
