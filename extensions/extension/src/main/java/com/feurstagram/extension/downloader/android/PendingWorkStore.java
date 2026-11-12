package com.feurstagram.extension.downloader.android;

import android.content.Context;
import android.content.SharedPreferences;

import com.feurstagram.extension.downloader.core.DownloadKind;
import com.feurstagram.extension.downloader.core.MediaItem;
import com.feurstagram.extension.downloader.core.PendingWorkCodec;

import java.util.ArrayList;
import java.util.List;

/**
 * Durable mirror of the accepted-but-unfinished download queue.
 *
 * <p>The terminal download history cannot answer "what was in flight when the
 * process died", so every accepted item is written here before dispatch and
 * removed exactly once when its task reaches a terminal state. On the next
 * start the ledger is replayed into the queue, where duplicate detection
 * prevents a second copy of the same item.
 *
 * <p>The store is bounded by {@link PendingWorkCodec#MAX_ENTRIES} and a corrupt
 * ledger is cleared instead of crashing the downloader. No Instagram state,
 * cookie or credential is involved: only the {@link MediaItem} the share
 * importer produced.
 */
final class PendingWorkStore {

    static final String PREFS_NAME = "hmx_download_pending";
    private static final String KEY_ITEMS = "items";

    private final SharedPreferences preferences;

    PendingWorkStore(Context context) {
        if (context == null) {
            throw new IllegalArgumentException("context must not be null");
        }
        this.preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /** Appends an accepted item; a repeated id is ignored. */
    synchronized void add(MediaItem item) {
        if (item == null) {
            throw new IllegalArgumentException("item must not be null");
        }
        List<MediaItem> current = read();
        for (MediaItem existing : current) {
            if (existing.id().equals(item.id())) {
                return;
            }
        }
        List<MediaItem> next = new ArrayList<MediaItem>(current.size() + 1);
        next.addAll(current);
        next.add(item);
        write(next);
    }

    /** Removes the item with this id; missing ids are ignored. */
    synchronized void remove(String itemId) {
        if (itemId == null || itemId.isEmpty()) {
            return;
        }
        List<MediaItem> current = read();
        List<MediaItem> next = new ArrayList<MediaItem>(current.size());
        boolean changed = false;
        for (MediaItem existing : current) {
            if (existing.id().equals(itemId)) {
                changed = true;
            } else {
                next.add(existing);
            }
        }
        if (changed) {
            write(next);
        }
    }

    /** Current ledger, oldest first. */
    synchronized List<MediaItem> snapshot() {
        return read();
    }

    synchronized void clear() {
        preferences.edit().remove(KEY_ITEMS).commit();
    }

    private List<MediaItem> read() {
        String encoded = preferences.getString(KEY_ITEMS, "");
        if (encoded == null || encoded.isEmpty()) {
            return new ArrayList<MediaItem>();
        }
        try {
            return new ArrayList<MediaItem>(PendingWorkCodec.decode(encoded));
        } catch (IllegalArgumentException e) {
            // A corrupt ledger must never block saving; the queue rebuilds.
            preferences.edit().remove(KEY_ITEMS).commit();
            return new ArrayList<MediaItem>();
        }
    }

    private void write(List<MediaItem> items) {
        String encoded = PendingWorkCodec.encode(items);
        if (!preferences.edit().putString(KEY_ITEMS, encoded).commit()) {
            throw new IllegalStateException("could not persist pending work");
        }
    }
}
