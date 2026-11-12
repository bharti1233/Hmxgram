package com.feurstagram.extension.downloader.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.feurstagram.extension.downloader.core.DownloadState;
import com.feurstagram.extension.downloader.core.DownloaderRow;

import java.util.ArrayList;
import java.util.List;

/**
 * The HMX Insta downloader screen.
 *
 * <p>The screen is a view over the engine, never a second source of truth:
 * every row is built from {@link HmxDownloader#rows()}, which projects the
 * queue and the persisted history. A row is only shown as saved when the
 * engine finished the task with a published path.
 *
 * <p>The screen offers exactly the actions the engine allows for a row:
 * cancel while the task is active, retry when the failure is recoverable and
 * attempts remain, and delete for a finished entry. Deleting removes the
 * history entry; the file that was already written to the gallery stays.
 *
 * <p>It uses platform widgets only (no AndroidX, no third-party dependency)
 * and touches no Instagram class. The refresh is driven by the engine's change
 * listener plus a slow poll, so a transfer started from the share target also
 * shows up here.
 */
public final class DownloaderActivity extends Activity {

    private static final long POLL_INTERVAL_MS = 500L;
    private static final int COLOR_OK = 0xFF1B5E20;
    private static final int COLOR_BAD = 0xFFB71C1C;
    private static final int COLOR_MUTED = 0xFF616161;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<DownloaderRow> rows = new ArrayList<DownloaderRow>();

    private HmxDownloader downloader;
    private ListView list;
    private TextView empty;
    private RowAdapter adapter;
    private boolean resumed;

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            if (!resumed) {
                return;
            }
            refresh();
            handler.postDelayed(this, POLL_INTERVAL_MS);
        }
    };

    private final HmxDownloader.Listener listener =
            new HmxDownloader.Listener() {
                @Override
                public void onDownloaderChanged() {
                    // The engine calls this from its worker threads.
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (resumed) {
                                refresh();
                            }
                        }
                    });
                }
            };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        downloader = HmxDownloader.get(getApplicationContext());
        setContentView(buildContent());
        adapter = new RowAdapter();
        list.setAdapter(adapter);
        // The first start reads the preferences and the pending ledger and
        // sweeps the cache: that is disk work, so it does not run on the UI
        // thread. The engine is idempotent, so a later call is a no-op.
        new Thread(new Runnable() {
            @Override
            public void run() {
                downloader.start();
            }
        }, "hmx-downloader-start").start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        downloader.setListener(listener);
        refresh();
        handler.postDelayed(poll, POLL_INTERVAL_MS);
    }

    @Override
    protected void onPause() {
        resumed = false;
        handler.removeCallbacks(poll);
        downloader.setListener(null);
        super.onPause();
    }

    private View buildContent() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(16);
        root.setPadding(padding, padding, padding, padding);

        TextView title = new TextView(this);
        title.setText("HMX Insta Downloader");
        title.setTextSize(22f);
        title.setGravity(Gravity.START);
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("Queued, active and finished downloads of this app.");
        subtitle.setTextSize(13f);
        subtitle.setTextColor(COLOR_MUTED);
        root.addView(subtitle);

        list = new ListView(this);
        list.setDividerHeight(dp(1));
        root.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        empty = new TextView(this);
        empty.setText("No downloads yet. Share a photo, a video or an audio file to HMX Insta and it will appear here.");
        empty.setTextSize(14f);
        empty.setTextColor(COLOR_MUTED);
        empty.setGravity(Gravity.CENTER);
        root.addView(empty);

        Button clear = new Button(this);
        clear.setText("Clear finished entries");
        clear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                confirmClear();
            }
        });
        root.addView(clear);
        return root;
    }

    private void confirmClear() {
        new AlertDialog.Builder(this)
                .setTitle("Clear finished entries")
                .setMessage("Remove every finished entry from the list? "
                        + "The files already saved in the gallery are not deleted.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Clear", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        downloader.clearFinished();
                        refresh();
                    }
                })
                .show();
    }

    private void refresh() {
        List<DownloaderRow> current = downloader.rows();
        if (sameRows(current)) {
            return;
        }
        rows.clear();
        rows.addAll(current);
        adapter.notifyDataSetChanged();
        empty.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
        list.setVisibility(rows.isEmpty() ? View.GONE : View.VISIBLE);
    }

    /**
     * Whether the engine's rows are the ones already shown. The poll runs
     * twice a second, so an unchanged list must not redraw the list.
     */
    private boolean sameRows(List<DownloaderRow> current) {
        if (current.size() != rows.size()) {
            return false;
        }
        for (int index = 0; index < current.size(); index++) {
            if (!current.get(index).equals(rows.get(index))) {
                return false;
            }
        }
        return true;
    }

    private void act(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }

    /** Renders the engine's rows. */
    private final class RowAdapter extends BaseAdapter {

        @Override
        public int getCount() {
            return rows.size();
        }

        @Override
        public DownloaderRow getItem(int position) {
            return rows.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            final DownloaderRow row = rows.get(position);
            LinearLayout holder = (LinearLayout) convertView;
            if (holder == null) {
                holder = buildRow();
            }
            bindRow(holder, row);
            return holder;
        }

        private LinearLayout buildRow() {
            LinearLayout holder = new LinearLayout(DownloaderActivity.this);
            holder.setOrientation(LinearLayout.VERTICAL);
            holder.setPadding(dp(8), dp(8), dp(8), dp(8));

            TextView name = new TextView(DownloaderActivity.this);
            name.setTextSize(15f);
            holder.addView(name);

            TextView status = new TextView(DownloaderActivity.this);
            status.setTextSize(13f);
            holder.addView(status);

            LinearLayout actions = new LinearLayout(DownloaderActivity.this);
            actions.setOrientation(LinearLayout.HORIZONTAL);
            holder.addView(actions);

            LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            actions.addView(actionButton("Retry"), buttonParams);
            actions.addView(actionButton("Cancel"), buttonParams);
            actions.addView(actionButton("Delete"), buttonParams);
            return holder;
        }

        private Button actionButton(String text) {
            Button button = new Button(DownloaderActivity.this);
            button.setText(text);
            button.setTextSize(12f);
            return button;
        }

        private void bindRow(LinearLayout holder, DownloaderRow row) {
            TextView name = (TextView) holder.getChildAt(0);
            TextView status = (TextView) holder.getChildAt(1);
            LinearLayout actions = (LinearLayout) holder.getChildAt(2);

            name.setText(row.title());
            status.setText(row.statusLabel());
            if (row.isVerified()) {
                status.setTextColor(COLOR_OK);
            } else if (row.state() == DownloadState.FAILED) {
                status.setTextColor(COLOR_BAD);
            } else {
                status.setTextColor(COLOR_MUTED);
            }

            Button retry = (Button) actions.getChildAt(0);
            Button cancel = (Button) actions.getChildAt(1);
            Button delete = (Button) actions.getChildAt(2);
            retry.setVisibility(row.canRetry() ? View.VISIBLE : View.GONE);
            cancel.setVisibility(row.canCancel() ? View.VISIBLE : View.GONE);
            delete.setVisibility(row.canDelete() ? View.VISIBLE : View.GONE);

            // The listeners are bound per row: the view is recycled, so a
            // listener captured when the view was created would act on a
            // different item later.
            retry.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    onRetry(row);
                }
            });
            cancel.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    onCancel(row);
                }
            });
            delete.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    onDelete(row);
                }
            });
        }
    }

    private void onRetry(DownloaderRow row) {
        if (downloader.retry(row.id())) {
            act("Retrying");
        } else {
            act("This download cannot be retried");
        }
        refresh();
    }

    private void onCancel(DownloaderRow row) {
        if (downloader.cancel(row.id())) {
            act("Cancelled");
        } else {
            act("This download is no longer running");
        }
        refresh();
    }

    private void onDelete(DownloaderRow row) {
        if (downloader.delete(row.id())) {
            act("Entry removed");
        } else {
            act("The entry could not be removed");
        }
        refresh();
    }
}
