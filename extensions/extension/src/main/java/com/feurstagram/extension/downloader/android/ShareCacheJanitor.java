package com.feurstagram.extension.downloader.android;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import com.feurstagram.extension.downloader.core.CacheRetentionPolicy;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Bounded cleanup for the private share cache and interrupted temp files.
 *
 * <p>Failed downloads keep their cached copy for a manual retry, and a process
 * kill can leave {@code cache/hmx-download-*.part} files behind. Without a
 * bound both would grow forever. The janitor deletes only entries that are
 * <em>not</em> referenced by an active task, and only past the grace period or
 * needed to respect the byte and file-count budgets, so a running download can
 * never lose its source.
 *
 * <p>Call discipline: the sweep runs at downloader start (before any resumed
 * task dispatches) and whenever the queue becomes idle. It is never called
 * while work is active, so a live {@code .part} or extraction file is never a
 * deletion candidate.
 *
 * <p>The two directories are swept with different rules. A share copy is
 * protected by name, because an active task references it through its
 * {@code content://} URI. A temporary download file is referenced by nothing —
 * only the running worker knows it — so the caller must pass
 * {@code allowTempSweep} only when no task is running. The caller enforces
 * that with the queue's own idle state; this class never guesses.
 */
final class ShareCacheJanitor {

    private static final String TAG = "HmxDownloader";

    private ShareCacheJanitor() {
    }

    /**
     * Applies {@link CacheRetentionPolicy} to the cache directories.
     *
     * @param context any context
     * @param referencedUrls content URIs of files an active task still needs
     * @param allowTempSweep whether no task is running, so a temporary
     *        download file may be reclaimed
     */
    static void sweep(Context context, Set<String> referencedUrls,
            boolean allowTempSweep) {
        if (context == null) {
            return;
        }
        Set<String> referencedNames = new HashSet<String>();
        if (referencedUrls != null) {
            for (String url : referencedUrls) {
                String name = shareName(context, url);
                if (name != null) {
                    referencedNames.add(name);
                }
            }
        }
        sweepDirectory(ShareCacheProvider.cacheDirectory(context), referencedNames);
        // A temporary download file is referenced by nothing but the running
        // worker, so it is only swept when the caller says no task is active.
        if (allowTempSweep) {
            sweepDirectory(context.getCacheDir(), new HashSet<String>());
        }
    }

    private static void sweepDirectory(File directory, Set<String> referencedNames) {
        File[] files = directory == null ? null : directory.listFiles();
        if (files == null || files.length == 0) {
            return;
        }
        List<CacheRetentionPolicy.Entry> entries =
                new ArrayList<CacheRetentionPolicy.Entry>(files.length);
        for (File file : files) {
            if (!file.isFile()) {
                continue;
            }
            entries.add(new CacheRetentionPolicy.Entry(
                    file.getName(), file.length(), file.lastModified(),
                    referencedNames.contains(file.getName())));
        }
        List<String> evicted = CacheRetentionPolicy.selectEvictions(
                entries, System.currentTimeMillis());
        for (String name : evicted) {
            File file = new File(directory, name);
            if (!file.delete() && file.exists()) {
                Log.w(TAG, "could not delete stale cache file " + name);
            }
        }
    }

    private static String shareName(Context context, String url) {
        if (url == null || !ShareCacheProvider.isShareUri(context, url)) {
            return null;
        }
        try {
            List<String> segments = Uri.parse(url).getPathSegments();
            if (segments.size() == 1) {
                return segments.get(0);
            }
        } catch (RuntimeException ignored) {
            // Not a provider URI we understand; it protects nothing.
        }
        return null;
    }
}
