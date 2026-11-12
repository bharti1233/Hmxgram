package com.feurstagram.extension.downloader.android;

import android.content.Context;
import android.content.SharedPreferences;

import com.feurstagram.extension.downloader.core.DownloadHistoryCodec;
import com.feurstagram.extension.downloader.core.DownloadRepository;
import com.feurstagram.extension.downloader.core.DownloadState;
import com.feurstagram.extension.downloader.core.DownloadTask;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * {@link DownloadRepository} backed by the app's own preferences file.
 *
 * <p>The history is stored as the versioned text produced by
 * {@link DownloadHistoryCodec}. A corrupt value is removed instead of being
 * allowed to crash a download; the next successful save writes a clean
 * history again. Entries are newest first and capped at the documented 100.
 *
 * <p>No network, credential or Instagram state is involved.
 */
public final class PreferencesDownloadRepository implements DownloadRepository {

    public static final String PREFS_NAME = "hmx_download_history";
    public static final String KEY_HISTORY = "history";

    private final SharedPreferences preferences;

    public PreferencesDownloadRepository(Context context) {
        if (context == null) {
            throw new IllegalArgumentException("context must not be null");
        }
        this.preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    @Override
    public synchronized void save(DownloadTask task) {
        if (task == null) {
            throw new IllegalArgumentException("task must not be null");
        }
        if (!task.state().isTerminal()) {
            throw new IllegalArgumentException(
                    "only finished tasks can be stored, found " + task.state());
        }
        List<DownloadTask> current = load();
        List<DownloadTask> next = new ArrayList<DownloadTask>(current.size() + 1);
        next.add(task.snapshot());
        for (DownloadTask existing : current) {
            if (!existing.id().equals(task.id())) {
                next.add(existing);
            }
        }
        while (next.size() > DownloadHistoryCodec.MAX_ENTRIES) {
            next.remove(next.size() - 1);
        }
        write(next);
    }

    @Override
    public synchronized List<DownloadTask> history() {
        return snapshots(load());
    }

    @Override
    public synchronized List<DownloadTask> history(int limit) {
        if (limit < 0) {
            throw new IllegalArgumentException("limit must not be negative");
        }
        List<DownloadTask> all = load();
        return snapshots(all.subList(0, Math.min(limit, all.size())));
    }

    @Override
    public synchronized boolean contains(String duplicateKey) {
        if (duplicateKey == null || duplicateKey.trim().isEmpty()) {
            throw new IllegalArgumentException("duplicateKey must not be blank");
        }
        for (DownloadTask task : load()) {
            if (task.state() == DownloadState.DONE
                    && task.duplicateKey().equals(duplicateKey)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public synchronized int size() {
        return load().size();
    }

    @Override
    public synchronized void clear() {
        preferences.edit().remove(KEY_HISTORY).commit();
    }

    private List<DownloadTask> load() {
        String encoded = preferences.getString(KEY_HISTORY, "");
        if (encoded == null || encoded.isEmpty()) {
            return new ArrayList<DownloadTask>();
        }
        try {
            return new ArrayList<DownloadTask>(DownloadHistoryCodec.decode(encoded));
        } catch (IllegalArgumentException e) {
            // Corrupt history is discarded: duplicate detection rebuilds from
            // future downloads and a bad value must never block saving.
            preferences.edit().remove(KEY_HISTORY).commit();
            return new ArrayList<DownloadTask>();
        }
    }

    private void write(List<DownloadTask> tasks) {
        String encoded = DownloadHistoryCodec.encode(tasks);
        if (!preferences.edit().putString(KEY_HISTORY, encoded).commit()) {
            throw new IllegalStateException("could not persist the download history");
        }
    }

    private static List<DownloadTask> snapshots(List<DownloadTask> source) {
        List<DownloadTask> copy = new ArrayList<DownloadTask>(source.size());
        for (DownloadTask task : source) {
            copy.add(task.snapshot());
        }
        return Collections.unmodifiableList(copy);
    }
}
