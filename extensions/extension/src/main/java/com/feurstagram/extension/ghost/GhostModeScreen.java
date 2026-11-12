package com.feurstagram.extension.ghost;

import android.content.Context;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.feurstagram.extension.ExpressiveSwitch;
import com.feurstagram.extension.Settings;

import java.util.Map;

/**
 * The HMX Ghost Mode page.
 *
 * Every control shows its title, a short explanation and its status. A
 * control Instagram decides on the server is shown as not supported and cannot
 * be switched on: a toggle that pretends to work would be worse than no
 * toggle at all.
 */
public final class GhostModeScreen {

    private GhostModeScreen() {
    }

    /** Fills the ghost-mode page. */
    public static void build(Context context, LinearLayout column) {
        if (context == null || column == null) return;

        Settings.addSection(context, column, "HMX Ghost Mode");

        LinearLayout master = Settings.addGroup(context, column);
        addToggle(context, master, "Master Ghost Mode",
                "Turns the verified ghost-mode controls below on or off. It never touches "
                        + "anything Instagram decides on its servers.",
                null, true, GhostMode.isMasterEnabled(context));
        Settings.sealGroup(context, master);

        Map<GhostFeature, Boolean> active = GhostMode.active(context);

        LinearLayout stories = Settings.addGroup(context, column);
        Settings.addSection(context, column, "Stories");
        for (GhostFeature feature : new GhostFeature[]{
                GhostFeature.HIDE_STORY_SEEN, GhostFeature.ANONYMOUS_STORY_VIEWING}) {
            addToggle(context, stories, feature.title(), feature.explanation(), feature,
                    feature.support() != GhostFeature.Support.UNSUPPORTED,
                    GhostMode.isEnabled(context, feature));
        }
        Settings.sealGroup(context, stories);

        LinearLayout messages = Settings.addGroup(context, column);
        Settings.addSection(context, column, "Messages");
        for (GhostFeature feature : new GhostFeature[]{
                GhostFeature.HIDE_MESSAGE_SEEN, GhostFeature.HIDE_TYPING_STATUS}) {
            addToggle(context, messages, feature.title(), feature.explanation(), feature,
                    feature.support() != GhostFeature.Support.UNSUPPORTED,
                    GhostMode.isEnabled(context, feature));
        }
        Settings.sealGroup(context, messages);

        LinearLayout activity = Settings.addGroup(context, column);
        Settings.addSection(context, column, "Activity");
        for (GhostFeature feature : new GhostFeature[]{
                GhostFeature.HIDE_ONLINE_STATUS, GhostFeature.DISABLE_ACTIVITY_STATUS,
                GhostFeature.HIDE_LIVE_VIEW}) {
            addToggle(context, activity, feature.title(), feature.explanation(), feature,
                    feature.support() != GhostFeature.Support.UNSUPPORTED,
                    GhostMode.isEnabled(context, feature));
        }
        Settings.sealGroup(context, activity);

        boolean anyActive = false;
        for (Boolean value : active.values()) {
            if (Boolean.TRUE.equals(value)) anyActive = true;
        }
        TextView summary = new TextView(context);
        summary.setText(anyActive
                ? "Some ghost-mode controls are in force."
                : "No ghost-mode control is in force right now.");
        Settings.body(summary);
        summary.setPadding(0, Settings.dp(context, 12), 0, 0);
        column.addView(summary);
    }

    /**
     * One control: title, explanation, status and a toggle.
     *
     * @param feature  the control, or null for the master switch
     * @param enabled  whether the toggle may be used
     * @param checked  the persisted state
     */
    private static void addToggle(Context context, LinearLayout group, String title, String explanation,
                                  GhostFeature feature, boolean enabled, boolean checked) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.VERTICAL);

        TextView label = new TextView(context);
        label.setText(title);
        Settings.titleLarge(label);
        row.addView(label);

        TextView note = new TextView(context);
        note.setText(explanation);
        Settings.body(note);
        note.setPadding(0, Settings.dp(context, 4), 0, 0);
        row.addView(note);

        String status = feature == null ? "Controls every supported option below"
                : feature.statusLabel();
        TextView state = new TextView(context);
        state.setText(status);
        Settings.body(state);
        state.setPadding(0, Settings.dp(context, 2), 0, 0);
        row.addView(state);

        ExpressiveSwitch toggle = new ExpressiveSwitch(context);
        toggle.setChecked(checked);
        toggle.setContentDescription(title);
        toggle.setEnabled(enabled);
        if (!enabled) {
            // A control that cannot work is shown off and cannot be turned on.
            toggle.setChecked(false);
        }
        toggle.setOnClickListener(v -> {
            if (!enabled) {
                toggle.setChecked(false);
                return;
            }
            boolean value = toggle.isChecked();
            if (feature == null) {
                GhostMode.setMasterEnabled(context, value);
            } else {
                GhostMode.setEnabled(context, feature, value);
            }
        });
        row.addView(toggle, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        group.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }
}
