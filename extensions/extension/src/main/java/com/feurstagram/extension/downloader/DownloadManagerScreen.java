package com.feurstagram.extension.downloader;

import android.content.Context;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.feurstagram.extension.Settings;

import java.util.List;

/**
 * The downloader's own page: what is downloading, what finished and what
 * failed. It lives in the HMX Insta settings, next to the rest of the app's
 * own surfaces.
 */
public final class DownloadManagerScreen {

    private DownloadManagerScreen() {
    }

    /** Fills the downloader page. */
    public static void build(Context context, LinearLayout column) {
        if (context == null || column == null) return;

        LinearLayout queue = Settings.addGroup(context, column);
        Settings.addSection(context, column, "Downloads");

        List<DownloadTask> pending = HmxDownloader.get().tasks();
        int waiting = 0;
        for (DownloadTask task : pending) {
            if (task.isFinished()) continue;
            waiting++;
            addTask(context, queue, task, true);
        }
        if (waiting == 0) {
            TextView empty = new TextView(context);
            empty.setText("Nothing is downloading");
            Settings.body(empty);
            queue.addView(empty);
        }
        Settings.sealGroup(context, queue);

        LinearLayout done = Settings.addGroup(context, column);
        Settings.addSection(context, column, "History");
        List<DownloadTask> history = HmxDownloader.get().history();
        if (history.isEmpty()) {
            TextView empty = new TextView(context);
            empty.setText("No downloads yet");
            Settings.body(empty);
            done.addView(empty);
        } else {
            for (DownloadTask task : history) {
                addTask(context, done, task, false);
            }
        }
        Settings.sealGroup(context, done);

        Button clear = Settings.makeButton(context, "Clear history",
                Settings.SURFACE_CONTAINER_HIGH, Settings.ON_SURFACE, true);
        clear.setOnClickListener(v -> {
            HmxDownloader.get().clearHistory();
            Toast.makeText(context, "Download history cleared", Toast.LENGTH_SHORT).show();
        });
        column.addView(clear, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    /** One row: the item, its state and, when it failed, a retry. */
    private static void addTask(Context context, LinearLayout group, DownloadTask task, boolean pending) {
        String label = task.label();
        String detail;
        if (task.state() == DownloadTask.State.RUNNING) {
            detail = task.bytesTotal() > 0 ? task.progress() + "%" : "starting";
        } else if (task.state() == DownloadTask.State.DONE) {
            detail = "saved to your gallery";
        } else if (task.state() == DownloadTask.State.CANCELLED) {
            detail = DownloadError.message(DownloadError.CANCELLED);
        } else if (task.state() == DownloadTask.State.FAILED) {
            detail = DownloadError.message(task.error());
        } else {
            detail = "queued";
        }

        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.VERTICAL);

        TextView title = new TextView(context);
        title.setText(label);
        Settings.body(title);
        row.addView(title);

        TextView state = new TextView(context);
        state.setText(detail);
        Settings.body(state);
        state.setPadding(0, Settings.dp(context, 2), 0, 0);
        row.addView(state);

        if (pending && task.state() == DownloadTask.State.RUNNING) {
            Button cancel = Settings.makeButton(context, "Cancel", 0, Settings.ON_SURFACE, false);
            cancel.setBackground(Settings.outlined(context, Settings.OUTLINE_VARIANT));
            cancel.setOnClickListener(v -> HmxDownloader.get().cancel(task));
            row.addView(cancel);
        } else if (!pending && task.state() == DownloadTask.State.FAILED
                && task.error() != null && DownloadError.isRecoverable(task.error())) {
            Button retry = Settings.makeButton(context, "Retry", 0, Settings.ON_SURFACE, false);
            retry.setBackground(Settings.outlined(context, Settings.OUTLINE_VARIANT));
            retry.setOnClickListener(v -> {
                if (!HmxDownloader.get().retry(context, task)) {
                    Toast.makeText(context, "This download cannot be retried", Toast.LENGTH_SHORT).show();
                }
            });
            row.addView(retry);
        }

        group.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }
}
