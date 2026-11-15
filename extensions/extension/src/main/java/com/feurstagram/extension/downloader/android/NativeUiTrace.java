package com.feurstagram.extension.downloader.android;

import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Bounded structural diagnostics for the in-context download action.
 *
 * <p>Two kinds of line are emitted, both under the {@code HmxNativeUI} tag:
 *
 * <ul>
 *   <li>{@code m12 event ...} — one structural observation, with a stable
 *       {@code surface}, {@code name}, {@code reason} and a sampled count;</li>
 *   <li>{@code m12 summary ...} — the derived state of a surface, printed when
 *       that state or its reason changes.</li>
 * </ul>
 *
 * <p>The derived state is exactly the vocabulary of the M12 diagnosis:
 * {@code NEVER_ARMED}, {@code ARMED_REFUSED}, {@code ARMED_INVISIBLE} and
 * {@code ATTACHED_VISIBLE}. A refusal always carries a reason code, so a
 * surface can never be "missing for no reason" in a captured log.
 *
 * <p>Privacy: nothing here reads text, content descriptions, tags, URLs,
 * account names or media values. It records class names, resource entry names,
 * geometry, alpha, visibility, attachment and stable reason codes only.
 */
public final class NativeUiTrace {

    /** The tag the M12 capture procedure filters on. */
    public static final String TAG = "HmxNativeUI";

    /** The surface a diagnostic belongs to. */
    public enum Surface {
        FEED, REELS;

        /** The stable, lower-case token used in a log line. */
        public String token() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    /** The derived classification of a surface. */
    public enum State {
        NEVER_ARMED, ARMED_REFUSED, ARMED_INVISIBLE, ATTACHED_VISIBLE
    }

    private static final int MAX_STAGES = 128;
    private static final int MAX_SUMMARIES = 40;
    private static final Map<String, Integer> COUNTS = new LinkedHashMap<String, Integer>();
    private static final Map<Surface, Treatment> TREATMENTS =
            new EnumMap<Surface, Treatment>(Surface.class);

    static {
        for (Surface surface : Surface.values()) {
            TREATMENTS.put(surface, new Treatment());
        }
    }

    private NativeUiTrace() {
    }

    /** The mutable record behind one surface. */
    private static final class Treatment {
        boolean armed;
        boolean created;
        boolean attached;
        boolean visible;
        int refused;
        int taps;
        int failures;
        int summaries;
        String reason = "NONE";
        State printedState;
        String printedReason;
    }

    // --- events and summaries ------------------------------------------

    /**
     * Records one observation.
     *
     * @param surface the surface it belongs to
     * @param name    a stable event name, upper case with underscores
     * @param reason  a stable reason code, or {@code NONE}
     * @param detail  a view or object to describe structurally, or {@code null}
     */
    public static synchronized void note(Surface surface, String name, String reason,
            Object detail) {
        try {
            Treatment treatment = TREATMENTS.get(surface);
            String key = surface.token() + "." + name;
            int count = bump(key);
            if (count > 3 && (count & (count - 1)) != 0) {
                return;
            }
            Log.i(TAG, "m12 event surface=" + surface.token()
                    + " name=" + name
                    + " reason=" + (reason == null ? "NONE" : reason)
                    + " count=" + count
                    + " detail=" + describe(detail));
            if (treatment != null) {
                publishSummary(surface, treatment);
            }
        } catch (Throwable ignored) {
            // Diagnostics must never affect the host.
        }
    }

    /** The hook is installed and can run. */
    public static synchronized void armed(Surface surface, Object detail) {
        Treatment treatment = treatment(surface);
        treatment.armed = true;
        note(surface, "ARMED", "NONE", detail);
    }

    /** A structural refusal: no control can be offered on this surface now. */
    public static synchronized void refused(Surface surface, String reason, Object detail) {
        Treatment treatment = treatment(surface);
        treatment.armed = true;
        treatment.created = false;
        treatment.attached = false;
        treatment.visible = false;
        treatment.refused++;
        treatment.reason = clean(reason);
        note(surface, "REFUSED", treatment.reason, detail);
    }

    /** A control was created but is not known to be visible yet. */
    public static synchronized void created(Surface surface, Object detail) {
        Treatment treatment = treatment(surface);
        treatment.created = true;
        treatment.reason = "CREATED";
        note(surface, "CREATED", "NONE", detail);
    }

    /** The control is attached to a window. */
    public static synchronized void attached(Surface surface, Object detail) {
        Treatment treatment = treatment(surface);
        treatment.attached = true;
        note(surface, "ATTACHED", "NONE", detail);
    }

    /** The control left its window. */
    public static synchronized void detached(Surface surface, Object detail) {
        Treatment treatment = treatment(surface);
        treatment.attached = false;
        treatment.visible = false;
        treatment.reason = "DETACHED";
        note(surface, "DETACHED", "DETACHED", detail);
    }

    /** The control is visible to the user. */
    public static synchronized void visible(Surface surface, Object detail) {
        Treatment treatment = treatment(surface);
        treatment.attached = true;
        treatment.visible = true;
        treatment.reason = "NONE";
        note(surface, "VISIBLE", "NONE", detail);
    }

    /**
     * The control exists but may not be shown, or was hidden after being shown.
     *
     * @param reason a stable reason code; never empty
     */
    public static synchronized void hidden(Surface surface, String reason, Object detail) {
        Treatment treatment = treatment(surface);
        treatment.visible = false;
        treatment.reason = clean(reason);
        note(surface, "HIDDEN", treatment.reason, detail);
    }

    /** The user tapped the control. */
    public static synchronized void tap(Surface surface, Object detail) {
        Treatment treatment = treatment(surface);
        treatment.taps++;
        note(surface, "TAP", "NONE", detail);
    }

    /** An unexpected failure was swallowed. */
    public static synchronized void failure(Surface surface, String reason, Object detail) {
        Treatment treatment = treatment(surface);
        treatment.failures++;
        treatment.reason = clean(reason);
        note(surface, "FAILED", treatment.reason, detail);
    }

    /** The current derived state of a surface. */
    public static synchronized State state(Surface surface) {
        Treatment treatment = TREATMENTS.get(surface);
        if (treatment == null || !treatment.armed) {
            return State.NEVER_ARMED;
        }
        if (treatment.visible && treatment.attached) {
            return State.ATTACHED_VISIBLE;
        }
        if (treatment.created) {
            return State.ARMED_INVISIBLE;
        }
        return State.ARMED_REFUSED;
    }

    /** The current reason code of a surface, or {@code NONE}. */
    public static synchronized String reason(Surface surface) {
        Treatment treatment = TREATMENTS.get(surface);
        if (treatment == null) {
            return "NONE";
        }
        if (!treatment.armed) {
            return "NOT_ARMED";
        }
        if (state(surface) == State.ARMED_REFUSED && "NONE".equals(treatment.reason)) {
            return "UNSPECIFIED";
        }
        return treatment.reason;
    }

    /** Prints one summary line per surface, unconditionally. */
    public static synchronized void summary() {
        for (Surface surface : Surface.values()) {
            Treatment treatment = TREATMENTS.get(surface);
            if (treatment != null) {
                printSummary(surface, treatment);
            }
        }
    }

    // --- legacy m11.1 trace --------------------------------------------

    /**
     * The M11 event, kept for compatibility with the earlier device notes.
     */
    public static synchronized void event(String stage, Object object) {
        try {
            int count = bump(stage);
            if (count > 3 && (count & (count - 1)) != 0) {
                return;
            }
            Log.i(TAG, "m11.1 stage=" + stage + " count=" + count + " "
                    + describe(object));
        } catch (Throwable ignored) {
            // Diagnostics must never affect the host.
        }
    }

    /** The M11 child dump, kept for compatibility. */
    public static void children(String stage, ViewGroup parent) {
        event(stage, parent);
        for (int i = 0; i < Math.min(16, parent.getChildCount()); i++) {
            event(stage + ".child" + i, parent.getChildAt(i));
        }
    }

    // --- internals ------------------------------------------------------

    private static Treatment treatment(Surface surface) {
        Treatment treatment = TREATMENTS.get(surface);
        if (treatment == null) {
            treatment = new Treatment();
            TREATMENTS.put(surface, treatment);
        }
        return treatment;
    }

    private static int bump(String key) {
        int count = COUNTS.containsKey(key) ? COUNTS.get(key) + 1 : 1;
        if (COUNTS.size() >= MAX_STAGES && !COUNTS.containsKey(key)) {
            return count;
        }
        COUNTS.put(key, count);
        return count;
    }

    private static String clean(String reason) {
        return reason == null || reason.trim().isEmpty() ? "UNSPECIFIED" : reason;
    }

    private static void publishSummary(Surface surface, Treatment treatment) {
        State state = state(surface);
        String reason = reason(surface);
        if (state == treatment.printedState && reason.equals(treatment.printedReason)) {
            return;
        }
        treatment.printedState = state;
        treatment.printedReason = reason;
        treatment.summaries++;
        if (treatment.summaries > MAX_SUMMARIES && treatment.summaries % 50 != 0) {
            return;
        }
        printSummary(surface, treatment);
    }

    private static void printSummary(Surface surface, Treatment treatment) {
        try {
            Log.i(TAG, "m12 summary surface=" + surface.token()
                    + " state=" + state(surface).name()
                    + " reason=" + reason(surface)
                    + " armed=" + flag(treatment.armed)
                    + " created=" + flag(treatment.created)
                    + " attached=" + flag(treatment.attached)
                    + " visible=" + flag(treatment.visible)
                    + " refused=" + treatment.refused
                    + " taps=" + treatment.taps
                    + " failures=" + treatment.failures);
        } catch (Throwable ignored) {
            // Diagnostics must never affect the host.
        }
    }

    private static int flag(boolean value) {
        return value ? 1 : 0;
    }

    /**
     * A structural description of an object.
     *
     * <p>For a view: type, resource entry name, attachment, shown state,
     * visibility, size, measured size, alpha, switch state, parent type and
     * layout params. For anything else: only the class name.
     */
    private static String describe(Object object) {
        if (object == null) {
            return "null";
        }
        String detail = object.getClass().getName();
        if (!(object instanceof View)) {
            return detail;
        }
        View view = (View) object;
        String id = "none";
        try {
            id = view.getResources().getResourceEntryName(view.getId());
        } catch (RuntimeException ignored) {
            // An unknown id is not an error.
        }
        detail += " id=" + id
                + " attached=" + view.isAttachedToWindow()
                + " shown=" + view.isShown()
                + " visibility=" + view.getVisibility()
                + " size=" + view.getWidth() + "x" + view.getHeight()
                + " bounds=" + view.getLeft() + "," + view.getTop()
                + "," + view.getRight() + "," + view.getBottom()
                + " measured=" + view.getMeasuredWidth() + "x" + view.getMeasuredHeight()
                + " alpha=" + view.getAlpha()
                + " clickable=" + view.isClickable()
                + " background=" + (view.getBackground() != null)
                + " enabled=" + DownloaderSwitch.isEnabled(view.getContext())
                + " parent=" + (view.getParent() == null ? "none"
                        : view.getParent().getClass().getName());
        if (view.getLayoutParams() != null) {
            detail += " lp=" + view.getLayoutParams().getClass().getName() + ":"
                    + view.getLayoutParams().width + "x" + view.getLayoutParams().height;
        }
        return detail;
    }
}
