package com.feurstagram.extension.downloader.android;

import android.content.Context;
import android.app.Notification;
import android.util.Log;

import com.feurstagram.extension.downloader.core.CancellationSignal;
import com.feurstagram.extension.downloader.core.DownloadError;
import com.feurstagram.extension.downloader.core.DownloadQueue;
import com.feurstagram.extension.downloader.core.DownloadRepository;
import com.feurstagram.extension.downloader.core.DownloadResult;
import com.feurstagram.extension.downloader.core.DownloadState;
import com.feurstagram.extension.downloader.core.DownloadTask;
import com.feurstagram.extension.downloader.core.DownloaderRow;
import com.feurstagram.extension.downloader.core.MediaItem;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Owns the download queue, the worker pool and the persisted history.
 *
 * <p>The queue enforces the concurrency and retry policy; this class only
 * dispatches runnable tasks to a fixed pool, records terminal tasks in
 * preferences and updates notifications. It is safe to call from the UI
 * thread, the share activity and the workers.
 *
 * <p>Process-death recovery: every accepted item is mirrored into
 * {@link PendingWorkStore} before dispatch and removed when it reaches a
 * terminal state, so a killed process never loses the queue silently. The
 * next start replays the ledger; {@link DownloadService} keeps the process in
 * the foreground while work exists. Instagram is not involved: enqueueing
 * only accepts a {@link MediaItem} built by the share importer or, in a later
 * milestone, by a settings page.
 */
public final class HmxDownloader {

    private static final String TAG = "HmxDownloader";

    private static volatile HmxDownloader instance;

    /**
     * Notified whenever the state the screen shows may have changed.
     *
     * <p>The callback runs on whichever thread caused the change, so a UI
     * implementation must hop to its own thread.
     */
    public interface Listener {
        void onDownloaderChanged();
    }

    private final Context context;
    private final DownloadQueue queue;
    private final DownloadRepository repository;
    private final DownloadNotifier notifier;
    private final PendingWorkStore pendingStore;
    private final ExecutorService workers;
    private final Object dispatchLock = new Object();
    private final Map<String, CancellationSignal> cancellations =
            new ConcurrentHashMap<String, CancellationSignal>();
    private final Map<String, Progress> progress =
            new ConcurrentHashMap<String, Progress>();

    private final AtomicInteger sessionSaved = new AtomicInteger();
    private final AtomicInteger sessionFailed = new AtomicInteger();
    private volatile boolean started;
    private volatile boolean shutdown;
    private volatile Runnable idleListener;
    private volatile Listener listener;

    /** The bytes a worker reported for one running task. */
    private static final class Progress {

        final long bytes;
        final long total;

        Progress(long bytes, long total) {
            this.bytes = bytes;
            this.total = total;
        }
    }

    private HmxDownloader(Context context) {
        this.context = context.getApplicationContext();
        this.repository = new PreferencesDownloadRepository(this.context);
        this.pendingStore = new PendingWorkStore(this.context);
        this.notifier = new DownloadNotifier(this.context);
        this.queue = new DownloadQueue();
        this.workers = Executors.newFixedThreadPool(
                DownloadQueue.DEFAULT_MAX_CONCURRENT, new WorkerThreadFactory());
    }

    /** Returns the process-wide downloader. */
    public static HmxDownloader get(Context context) {
        if (context == null) {
            throw new IllegalArgumentException("context must not be null");
        }
        HmxDownloader current = instance;
        if (current == null) {
            synchronized (HmxDownloader.class) {
                current = instance;
                if (current == null) {
                    current = new HmxDownloader(context);
                    instance = current;
                }
            }
        }
        return current;
    }

    /**
     * Seeds duplicate detection from the saved history and replays the pending
     * ledger of accepted-but-unfinished items. Waiting work is dispatched
     * immediately; nothing is lost to a process restart.
     */
    public void start() {
        if (started || shutdown) {
            return;
        }
        synchronized (dispatchLock) {
            if (started || shutdown) {
                return;
            }
            started = true;
            try {
                queue.seedSaved(repository);
            } catch (RuntimeException e) {
                Log.w(TAG, "could not seed download history", e);
            }
            recoverPendingLocked();
        }
        // The sweep runs before the first dispatch: the share copies of the
        // replayed items are protected by the pending ledger, and no task is
        // running yet, so no temporary file can be reclaimed by mistake.
        sweepCache();
        dispatch();
    }

    /**
     * Validates and enqueues an item, then starts a worker when a slot is free
     * and keeps the process foregrounded until the queue drains.
     */
    public DownloadQueue.EnqueueResult enqueue(MediaItem item) {
        start();
        DownloadQueue.EnqueueResult result;
        synchronized (dispatchLock) {
            result = queue.enqueue(item);
        }
        if (result.isAdded()) {
            // Persist before dispatch: the ledger must not miss a task that a
            // fast worker could finish before the store is written.
            try {
                pendingStore.add(item);
            } catch (RuntimeException e) {
                Log.w(TAG, "could not persist pending work", e);
            }
            dispatch();
            DownloadService.ensureRunning(context);
        }
        return result;
    }

    /** Enqueues every item, ignoring duplicates and a full queue. */
    public int enqueueAll(List<MediaItem> items) {
        if (items == null) {
            throw new IllegalArgumentException("items must not be null");
        }
        int added = 0;
        for (MediaItem item : items) {
            if (enqueue(item).isAdded()) {
                added++;
            }
        }
        return added;
    }

    /**
     * Cancels a waiting or running task. The signal reaches the worker at
     * every checkpoint, including inside the MediaStore publish, so a
     * cancelled task cannot leave a published file behind.
     *
     * @return whether the task was active
     */
    public boolean cancel(String taskId) {
        if (taskId == null) {
            return false;
        }
        CancellationSignal signal = cancellations.get(taskId);
        if (signal != null) {
            signal.cancel();
        }
        boolean active;
        synchronized (dispatchLock) {
            active = queue.cancel(taskId);
        }
        if (active) {
            recordTerminal(taskId);
            notifier.notifyProgress(queue.runningCount() + queue.waitingCount());
        }
        return active;
    }

    /**
     * Manually retries a failed task when the queue policy allows it.
     *
     * <p>Two cases are covered. A task that is still known to this process is
     * re-queued in place. A task that only exists in the persisted history
     * (the process was restarted after the failure) is enqueued again as a
     * new task, because the queue no longer holds it. In both cases the item
     * is written to the pending-work ledger before it is dispatched.
     *
     * @return whether the task was re-queued
     */
    public boolean retry(String taskId) {
        if (taskId == null || taskId.trim().isEmpty()) {
            return false;
        }
        boolean requeued;
        MediaItem item = null;
        synchronized (dispatchLock) {
            requeued = queue.retry(taskId);
            if (requeued) {
                DownloadTask task = queue.task(taskId);
                item = task == null ? null : task.item();
            } else {
                DownloadTask remembered = findRemembered(taskId);
                if (remembered == null || !remembered.canRetry()) {
                    return false;
                }
                item = remembered.item();
                DownloadQueue.EnqueueResult result;
                try {
                    result = queue.enqueue(item);
                } catch (IllegalArgumentException e) {
                    // The item is no longer downloadable (an expired link, a
                    // container that must be expanded first): the screen shows
                    // the failure instead of silently dropping the request.
                    Log.w(TAG, "cannot retry " + taskId, e);
                    return false;
                }
                requeued = result.isAdded();
            }
        }
        if (requeued && item != null) {
            // A failed task was removed from the ledger when it reached its
            // terminal state, so a retry has to be written back before it is
            // dispatched: a killed process must not lose it.
            try {
                pendingStore.add(item);
            } catch (RuntimeException e) {
                Log.w(TAG, "could not persist pending work", e);
            }
        }
        if (requeued) {
            dispatch();
            DownloadService.ensureRunning(context);
        }
        notifyChanged();
        return requeued;
    }

    /**
     * Removes one entry from the persisted history.
     *
     * <p>The downloaded file is not deleted: the entry only disappears from
     * the list and from duplicate detection. An active task cannot be
     * removed; cancel it first.
     *
     * @return whether an entry was removed
     */
    public boolean delete(String taskId) {
        if (taskId == null || taskId.trim().isEmpty()) {
            return false;
        }
        boolean removed;
        try {
            removed = repository.delete(taskId);
        } catch (RuntimeException e) {
            Log.w(TAG, "could not delete the history entry", e);
            return false;
        }
        if (removed) {
            notifyChanged();
        }
        return removed;
    }

    /**
     * The state the downloader screen shows: the active tasks first, then the
     * remembered history, newest first.
     */
    public List<DownloaderRow> rows() {
        start();
        List<DownloaderRow> rows = new ArrayList<DownloaderRow>();
        Set<String> seen = new HashSet<String>();
        List<DownloadTask> active = new ArrayList<DownloadTask>();
        for (DownloadTask task : queue.tasks()) {
            if (task.state().isActive()) {
                active.add(task);
            }
        }
        for (DownloadTask task : active) {
            seen.add(task.id());
            rows.add(rowFor(task));
        }
        for (DownloadTask task : repository.history()) {
            if (seen.add(task.id())) {
                rows.add(rowFor(task));
            }
        }
        return Collections.unmodifiableList(rows);
    }

    /**
     * Registers the screen's change listener. Only one listener is kept: the
     * downloader screen is the only consumer.
     */
    public void setListener(Listener listener) {
        this.listener = listener;
    }

    private DownloaderRow rowFor(DownloadTask task) {
        Progress current = progress.get(task.id());
        long bytes = current == null ? 0L : current.bytes;
        long total = current == null ? DownloaderRow.UNKNOWN_BYTES : current.total;
        return DownloaderRow.from(task, bytes, total);
    }

    private DownloadTask findRemembered(String taskId) {
        for (DownloadTask task : repository.history()) {
            if (task.id().equals(taskId)) {
                return task;
            }
        }
        return null;
    }

    private void notifyChanged() {
        Listener current = listener;
        if (current == null) {
            return;
        }
        try {
            current.onDownloaderChanged();
        } catch (RuntimeException e) {
            Log.w(TAG, "downloader listener failed", e);
        }
    }

    /** Persisted history, newest first. */
    public List<DownloadTask> history() {
        return repository.history();
    }

    /** Removes persisted history and remembered duplicate keys. */
    public void clearHistory() {
        repository.clear();
        pendingStore.clear();
        queue.clear();
    }

    /**
     * Removes only the finished entries.
     *
     * <p>The downloader screen offers this as "clear finished entries": the
     * running and waiting tasks stay untouched, and so does the pending-work
     * ledger.
     */
    public void clearFinished() {
        repository.clear();
    }

    /** Whether nothing is waiting and nothing is running. */
    public boolean isIdle() {
        synchronized (dispatchLock) {
            return queue.runningCount() == 0 && queue.waitingCount() == 0;
        }
    }

    /**
     * Callback for {@link DownloadService}: invoked whenever the queue became
     * idle after a terminal transition. May be {@code null}.
     */
    public void setIdleListener(Runnable listener) {
        this.idleListener = listener;
    }

    /**
     * The notification the foreground service publishes. Always builds,
     * regardless of the Android 13 notification permission.
     */
    public Notification foregroundNotification() {
        return notifier.foregroundNotification();
    }

    /** Visible for tests; a production process keeps the pool running. */
    public void shutdown() {
        shutdown = true;
        workers.shutdownNow();
    }

    private void recoverPendingLocked() {
        List<MediaItem> items;
        try {
            items = pendingStore.snapshot();
        } catch (RuntimeException e) {
            Log.w(TAG, "could not read pending work", e);
            return;
        }
        for (MediaItem item : items) {
            try {
                DownloadQueue.EnqueueResult result = queue.enqueue(item);
                if (!result.isAdded()) {
                    // Already saved, or the queue is full: the ledger entry
                    // can go either way.
                    pendingStore.remove(item.id());
                }
            } catch (IllegalArgumentException e) {
                pendingStore.remove(item.id());
            }
        }
    }

    private void dispatch() {
        List<DownloadTask> rejected = new ArrayList<DownloadTask>();
        synchronized (dispatchLock) {
            if (shutdown) {
                return;
            }
            for (final DownloadTask task : queue.startNext()) {
                final CancellationSignal cancelled = new CancellationSignal();
                cancellations.put(task.id(), cancelled);
                try {
                    workers.execute(new Runnable() {
                        @Override
                        public void run() {
                            runTask(task, cancelled);
                        }
                    });
                } catch (RejectedExecutionException e) {
                    cancelled.cancel();
                    cancellations.remove(task.id());
                    queue.cancel(task.id());
                    rejected.add(task);
                }
            }
        }
        // The terminal bookkeeping writes to preferences and the file system,
        // so it runs outside the dispatch lock.
        for (DownloadTask task : rejected) {
            recordTerminal(task.id());
        }
        if (!rejected.isEmpty()) {
            notifyChanged();
        }
    }

    private void runTask(DownloadTask task, CancellationSignal cancelled) {
        DownloadResult result;
        try {
            result = DownloadWorker.execute(context, task, cancelled,
                    new DownloadWorker.ProgressListener() {
                        @Override
                        public void onProgress(long bytesRead, long totalBytes) {
                            // The per-task counters drive the downloader
                            // screen; the notification only shows the count.
                            progress.put(task.id(), new Progress(bytesRead, totalBytes));
                            notifier.notifyProgress(
                                    queue.runningCount() + queue.waitingCount());
                        }
                    });
        } catch (RuntimeException e) {
            Log.w(TAG, "download worker failed", e);
            result = DownloadResult.failure(DownloadError.UNKNOWN);
        }

        boolean success = result.isSuccess();
        synchronized (dispatchLock) {
            DownloadTask current = queue.task(task.id());
            if (current != null && current.state() == DownloadState.RUNNING) {
                if (success) {
                    queue.complete(task.id(), result);
                } else {
                    queue.fail(task.id(), result.error());
                }
            } else {
                // The task was cancelled while the worker was busy. If the
                // worker had already published, remove that row: a cancelled
                // task never keeps a finished file.
                if (success) {
                    DownloadWorker.discardPublished(context, result.path());
                }
            }
            cancellations.remove(task.id());
        }
        // The task reached a terminal state: its live counters are stale.
        progress.remove(task.id());
        recordTerminal(task.id());
        notifier.notifyProgress(queue.runningCount() + queue.waitingCount());
        notifyChanged();
        dispatch();
    }

    private void recordTerminal(String taskId) {
        DownloadTask task = queue.task(taskId);
        if (task == null || !task.state().isTerminal()) {
            return;
        }
        try {
            pendingStore.remove(task.item().id());
        } catch (RuntimeException e) {
            Log.w(TAG, "could not update pending work", e);
        }
        try {
            repository.save(task.snapshot());
        } catch (RuntimeException e) {
            Log.w(TAG, "could not persist the download history", e);
        }
        if (task.state() == DownloadState.DONE
                || task.state() == DownloadState.CANCELLED) {
            ShareCacheProvider.deleteCopy(context, task.item().url());
        }
        // A permanent failure keeps its cache copy for a manual retry; the
        // janitor bounds it later.
        if (task.state() == DownloadState.DONE) {
            sessionSaved.incrementAndGet();
        } else if (task.state() == DownloadState.FAILED) {
            sessionFailed.incrementAndGet();
        }
        boolean idle;
        synchronized (dispatchLock) {
            idle = queue.runningCount() == 0 && queue.waitingCount() == 0;
        }
        if (idle) {
            int saved = sessionSaved.getAndSet(0);
            int failed = sessionFailed.getAndSet(0);
            notifier.notifyResult(saved, failed);
            sweepCache();
            Runnable listener = idleListener;
            if (listener != null) {
                try {
                    listener.run();
                } catch (RuntimeException e) {
                    Log.w(TAG, "idle listener failed", e);
                }
            }
        }
        notifyChanged();
    }

    /** Content URIs the active queue still reads from the share cache. */
    private Set<String> activeShareUrls() {
        Set<String> urls = new HashSet<String>();
        for (DownloadTask task : queue.tasks()) {
            if (task.state().isActive()) {
                urls.add(task.item().url());
            }
        }
        for (MediaItem item : pendingStore.snapshot()) {
            urls.add(item.url());
        }
        return urls;
    }

    private void sweepCache() {
        try {
            // A temporary download file is only reclaimed when no task is
            // running; a share copy is protected by name in either case.
            ShareCacheJanitor.sweep(context, activeShareUrls(), isIdle());
        } catch (RuntimeException e) {
            Log.w(TAG, "cache sweep failed", e);
        }
    }

    private static final class WorkerThreadFactory implements ThreadFactory {
        private final AtomicInteger sequence = new AtomicInteger();

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable,
                    "hmx-download-" + sequence.incrementAndGet());
            thread.setPriority(Thread.NORM_PRIORITY - 1);
            return thread;
        }
    }
}
