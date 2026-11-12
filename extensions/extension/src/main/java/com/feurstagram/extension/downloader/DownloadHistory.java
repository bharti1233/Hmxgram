package com.feurstagram.extension.downloader;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

/**
 * The download history: what was downloaded, when and whether it worked.
 *
 * The history is a convenience list, not a ledger: the gallery itself is the
 * record of what is on the device. It is stored in the app's own preferences
 * and never leaves the device.
 */
final class DownloadHistory {

    private static final String PREFS = "hmx_download_history";
    private static final String KEY_ENTRIES = "entries";
    private static final int MAX_ENTRIES = 100;

    private final SharedPreferences preferences;

    DownloadHistory() {
        this(null);
    }

    DownloadHistory(Context context) {
        if (context == null) {
            preferences = null;
        } else {
            preferences = context.getApplicationContext()
                    .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        }
    }

    /** Records that a download was queued. */
    void recordQueued(DownloadTask task) {
        if (task == null) return;
        String label = task.label() + " · " + (task.owner().isEmpty() ? "HMX Insta" : task.owner());
        store(task, label, DownloadTask.State.QUEUED.name(), "");
    }

    /** Records how a download ended. */
    void recordFinished(DownloadTask task) {
        if (task == null) return;
        String label = task.label() + " · " + (task.owner().isEmpty() ? "HMX Insta" : task.owner());
        String state = task.state().name();
        String detail = task.error() == null ? "" : DownloadError.message(task.error());
        store(task, label, state, detail);
    }

    private void store(DownloadTask task, String label, String state, String detail) {
        if (preferences == null) return;
        String key = task.item() == null ? "unknown" : task.item().duplicateKey();
        SharedPreferences.Editor editor = preferences.edit();
        editor.putString(key + ".label", label);
        editor.putString(key + ".state", state);
        editor.putString(key + ".detail", detail);
        editor.putLong(key + ".at", System.currentTimeMillis());
        editor.putString(key + ".path", task.savedPath() == null ? "" : task.savedPath());
        editor.apply();
    }

    /** The finished downloads, newest first. */
    List<DownloadTask> entries() {
        List<DownloadTask> out = new ArrayList<>();
        if (preferences == null) return out;
        SharedPreferences all = preferences.getAll();
        List<String> keys = new ArrayList<>();
        for (String key : all.keySet()) {
            if (key.endsWith(".at")) keys.add(key.substring(0, key.length() - 3));
        }
        keys.sort((a, b) -> Long.compare(
                all.getLong(b + ".at", 0), all.getLong(a + ".at", 0)));
        for (String key : keys) {
            DownloadTask task = new DownloadTask(null, "",
                    all.getString(key + ".label", "HMX Insta"));
            String state = all.getString(key + ".state", DownloadTask.State.FAILED.name());
            if (DownloadTask.State.DONE.name().equals(state)) {
                task.succeed(all.getString(key + ".path", ""));
            } else if (DownloadTask.State.CANCELLED.name().equals(state)) {
                task.cancel();
            } else {
                task.fail(DownloadError.NETWORK);
            }
            out.add(task);
            if (out.size() >= MAX_ENTRIES) break;
        }
        return out;
    }

    /** Forgets the history. */
    void clear() {
        if (preferences == null) return;
        preferences.edit().clear().apply();
    }
}
