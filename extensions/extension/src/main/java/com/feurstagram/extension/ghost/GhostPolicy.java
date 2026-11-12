package com.feurstagram.extension.ghost;

/**
 * The rules that decide whether a privacy control is active.
 *
 * <p>Three things have to be true: the master switch is on, the control
 * itself is on, and the control is supported. A control that is not
 * supported can never be active, whatever the preferences say, so a stale
 * preference can never switch on something that does not work.
 *
 * <p>Pure-Java: no Android imports.
 */
public final class GhostPolicy {

    private GhostPolicy() {
    }

    /**
     * Whether a control is active.
     *
     * @param feature the control
     * @param masterOn the master switch
     * @param featureOn the control's own switch
     * @return whether the control must behave as if it were on
     */
    public static boolean isActive(GhostFeature feature, boolean masterOn,
            boolean featureOn) {
        if (feature == null) {
            throw new IllegalArgumentException("feature must not be null");
        }
        if (!feature.isSupported()) {
            return false;
        }
        return masterOn && featureOn;
    }

    /**
     * The effective state of a control after the master switch is applied.
     *
     * @return whether the control is on
     */
    public static boolean effective(boolean masterOn, boolean featureOn) {
        return masterOn && featureOn;
    }

    /**
     * Whether the screen may offer a switch for this control.
     *
     * <p>An unsupported control is shown with its reason and cannot be
     * switched on: a switch that does nothing would be a lie.
     */
    public static boolean canBeSwitched(GhostFeature feature) {
        if (feature == null) {
            throw new IllegalArgumentException("feature must not be null");
        }
        return feature.isSupported();
    }

    /**
     * Whether a stored preference may be turned on.
     *
     * <p>This is the same rule as {@link #canBeSwitched(GhostFeature)}: the
     * state layer refuses to store an enabled value for an unsupported
     * control.
     */
    public static boolean mayEnable(GhostFeature feature) {
        return canBeSwitched(feature);
    }
}
