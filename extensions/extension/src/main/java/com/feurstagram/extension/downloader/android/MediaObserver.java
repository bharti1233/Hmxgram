package com.feurstagram.extension.downloader.android;

import android.util.Log;

import com.feurstagram.extension.downloader.core.MediaModelReader;
import com.feurstagram.extension.downloader.core.MediaRegistry;
import com.feurstagram.extension.downloader.core.ObservedMedia;

import java.util.ArrayDeque;
import java.util.List;

/**
 * Watches the media the user browses and records what they looked at.
 *
 * <p>This is the read-only observation the downloader milestone plan calls
 * Tier 2: the Instagram hook hands over the model it was already given and
 * this class reads it. Nothing is modified, no view is touched, no view
 * hierarchy is scanned and no request is intercepted.
 *
 * <p>The reading happens away from the main thread. The hook only stores the
 * model reference, exactly as the plan requires, so a long feed cannot slow
 * the UI down.
 *
 * <p>Every failure is swallowed: a problem inside the observer must never
 * reach Instagram's code.
 */
public final class MediaObserver {

    private static final String TAG = "HmxDownloader";

    /** How many pending models are kept before the oldest is dropped. */
    private static final int MAX_PENDING = 32;

    private static final MediaRegistry REGISTRY = new MediaRegistry();
    private static final ArrayDeque<Object> PENDING = new ArrayDeque<Object>();
    private static final Object LOCK = new Object();

    private static volatile boolean enabled = true;
    private static boolean workerRunning;

    private MediaObserver() {
    }

    /** The observations, most recent first. */
    public static List<ObservedMedia> observed() {
        return REGISTRY.list();
    }

    /** The observation with this id, or {@code null}. */
    public static ObservedMedia find(String id) {
        return REGISTRY.find(id);
    }

    /** Forgets every observation. */
    public static void clear() {
        REGISTRY.clear();
    }

    /** Turns the observation on or off. */
    public static void setEnabled(boolean value) {
        enabled = value;
        if (!value) {
            synchronized (LOCK) {
                PENDING.clear();
            }
        }
    }

    public static boolean isEnabled() {
        return enabled;
    }

    /**
     * Records a model the user is looking at.
     *
     * <p>Called from the Instagram hook. It never blocks and never throws.
     *
     * @param model the media model Instagram handed to its row binder
     */
    public static void observeMedia(Object model) {
        if (!enabled || model == null) {
            return;
        }
        try {
            synchronized (LOCK) {
                while (PENDING.size() >= MAX_PENDING) {
                    PENDING.pollFirst();
                }
                PENDING.addLast(model);
            }
            ensureWorker();
        } catch (RuntimeException e) {
            // The observer must never break the caller.
            Log.w(TAG, "could not queue an observed media", e);
        }
    }

    private static void ensureWorker() {
        synchronized (LOCK) {
            if (workerRunning) {
                return;
            }
            workerRunning = true;
        }
        Thread thread = new Thread(new Worker(), "hmx-media-observer");
        thread.setPriority(Thread.MIN_PRIORITY);
        thread.setDaemon(true);
        thread.start();
    }

    /** Reads the queued models, one at a time. */
    private static final class Worker implements Runnable {

        @Override
        public void run() {
            long idleSince = System.currentTimeMillis();
            while (true) {
                Object model;
                synchronized (LOCK) {
                    model = PENDING.pollFirst();
                }
                if (model == null) {
                    // The thread only lives while there is something to read.
                    // A user who stopped browsing does not pay a wake-up every
                    // second for the rest of the session.
                    if (System.currentTimeMillis() - idleSince >= IDLE_EXIT_MS) {
                        synchronized (LOCK) {
                            if (PENDING.isEmpty()) {
                                workerRunning = false;
                                return;
                            }
                        }
                    }
                    try {
                        Thread.sleep(250L);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        synchronized (LOCK) {
                            workerRunning = false;
                        }
                        return;
                    }
                    continue;
                }
                idleSince = System.currentTimeMillis();
                try {
                    ObservedMedia media = MediaModelReader.read(model);
                    if (media != null) {
                        REGISTRY.observe(media);
                    }
                } catch (RuntimeException e) {
                    Log.w(TAG, "could not read an observed media", e);
                }
            }
        }
    }

    /** How long the reader waits for a model before it gives up the thread. */
    private static final long IDLE_EXIT_MS = 120000L;

    /** How many models are waiting to be read. */
    static int pendingCount() {
        synchronized (LOCK) {
            return PENDING.size();
        }
    }
}
