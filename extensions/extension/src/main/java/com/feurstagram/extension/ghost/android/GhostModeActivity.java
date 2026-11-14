package com.feurstagram.extension.ghost.android;

import com.feurstagram.extension.ghost.GhostFeature;
import com.feurstagram.extension.ghost.GhostPolicy;

import android.app.Activity;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Switch;
import android.widget.TextView;

/**
 * The Ghost Mode settings screen.
 *
 * <p>The screen lists every privacy control with a plain explanation of what
 * it does. A control that works has a switch; a control that cannot be
 * implemented safely shows the reason instead and cannot be switched on —
 * a switch that does nothing would be a lie.
 *
 * <p>The master switch turns every supported control off at once. It does not
 * erase the individual choices: switching it back on restores them.
 *
 * <p>The screen uses platform widgets only and touches no Instagram class.
 */
public final class GhostModeActivity extends Activity {

    private static final int COLOR_UNSUPPORTED = 0xFF616161;
    private static final int COLOR_TITLE = 0xFF212121;

    private GhostState state;
    private ListView list;
    private RowAdapter adapter;
    private Switch master;
    private TextView summary;

    /** Supported controls first, so their switches are visible immediately. */
    private final GhostFeature[] features = GhostFeature.supportedFirst();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        state = GhostState.get(getApplicationContext());
        setContentView(buildContent());
        adapter = new RowAdapter();
        list.setAdapter(adapter);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private View buildContent() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(16);
        root.setPadding(padding, padding, padding, padding);

        TextView title = new TextView(this);
        title.setText("HMX Ghost Mode");
        title.setTextSize(22f);
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("Privacy controls. Every control starts off.");
        subtitle.setTextSize(13f);
        subtitle.setTextColor(COLOR_UNSUPPORTED);
        root.addView(subtitle);

        master = new Switch(this);
        master.setText("Ghost Mode");
        master.setTextSize(18f);
        master.setTypeface(Typeface.DEFAULT_BOLD);
        master.setChecked(state.isMasterOn());
        master.setOnCheckedChangeListener(new android.widget.CompoundButton
                .OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton button,
                    boolean checked) {
                state.setMasterOn(checked);
                refresh();
            }
        });
        root.addView(master);

        summary = new TextView(this);
        summary.setTextSize(13f);
        summary.setTextColor(COLOR_UNSUPPORTED);
        summary.setPadding(0, dp(4), 0, dp(8));
        root.addView(summary);

        list = new ListView(this);
        list.setDividerHeight(dp(1));
        root.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return root;
    }

    private void refresh() {
        if (master != null && master.isChecked() != state.isMasterOn()) {
            master.setChecked(state.isMasterOn());
        }
        if (summary != null) {
            int supported = 0;
            for (GhostFeature feature : features) {
                if (feature.isSupported()) {
                    supported++;
                }
            }
            summary.setText(state.isMasterOn()
                    ? "On — " + supported + " supported control" + (supported == 1 ? "" : "s")
                            + " can be enabled below. Saved choices are kept when you turn it off."
                    : "Off — supported controls are inactive. Your saved choices are kept;"
                            + " turn the master switch on to re-activate them.");
        }
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }

    /** One row per privacy control. */
    private final class RowAdapter extends BaseAdapter {

        @Override
        public int getCount() {
            return features.length;
        }

        @Override
        public Object getItem(int position) {
            return features[position];
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            final GhostFeature feature = features[position];
            LinearLayout holder = (LinearLayout) convertView;
            if (holder == null) {
                holder = buildRow();
            }
            bindRow(holder, feature);
            return holder;
        }

        private LinearLayout buildRow() {
            LinearLayout holder = new LinearLayout(GhostModeActivity.this);
            holder.setOrientation(LinearLayout.VERTICAL);
            holder.setPadding(dp(8), dp(8), dp(8), dp(8));

            TextView name = new TextView(GhostModeActivity.this);
            name.setTextSize(16f);
            name.setTypeface(Typeface.DEFAULT_BOLD);
            holder.addView(name);

            TextView explanation = new TextView(GhostModeActivity.this);
            explanation.setTextSize(13f);
            holder.addView(explanation);

            Switch toggle = new Switch(GhostModeActivity.this);
            toggle.setText("Enabled");
            toggle.setTextSize(12f);
            holder.addView(toggle);
            return holder;
        }

        private void bindRow(LinearLayout holder, GhostFeature feature) {
            TextView name = (TextView) holder.getChildAt(0);
            TextView explanation = (TextView) holder.getChildAt(1);
            Switch toggle = (Switch) holder.getChildAt(2);

            name.setText(feature.title());
            if (feature.isSupported()) {
                explanation.setText(feature.explanation());
                explanation.setTextColor(COLOR_UNSUPPORTED);
                toggle.setVisibility(View.VISIBLE);
                toggle.setEnabled(state.isMasterOn());
                boolean on = state.isFeatureOn(feature);
                toggle.setChecked(on);
                toggle.setText(on ? "On" : "Off");
                toggle.setOnCheckedChangeListener(
                        new android.widget.CompoundButton.OnCheckedChangeListener() {
                            @Override
                            public void onCheckedChanged(
                                    android.widget.CompoundButton button,
                                    boolean checked) {
                                state.setFeatureOn(feature, checked);
                                refresh();
                            }
                        });
            } else {
                // No switch and an explicit marker: the row must never look
                // like a control that is merely switched off.
                explanation.setText("UNSUPPORTED — " + feature.unsupportedReason());
                explanation.setTextColor(COLOR_UNSUPPORTED);
                toggle.setVisibility(View.GONE);
            }
        }
    }
}
