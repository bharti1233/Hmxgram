package com.feurstagram.extension.downloader.android;

import android.content.Context;
import android.util.Log;

import com.feurstagram.extension.downloader.core.DownloadError;
import com.feurstagram.extension.downloader.core.DownloadQueue;
import com.feurstagram.extension.downloader.core.DownloadRepository;
import com.feurstagram.extension.downloader.core.DownloadResult;
import com.feurstagram.extension.downloader.core.DownloadState;
import com.feurstagram.extension.downloader.core.DownloadTask;
import com.feurstagram.extension.downloader.core.MediaItem;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Owns the download queue, the worker pool and the persisted history.
 *
 * <p>The queue enforces the concurrency and retry policy; this class only
 * dispatches runnable tasks to a fixed pool, records terminal tasks in
 * preferences and updates notifications. It is safe to call from the UI
 * thread, the share activity and the workers.
 *
 * <p>Instagram is not involved: enqueueing only accepts a {@link MediaItem}
 * built by the share importer or, in a later milestone, by a settings page.
 */
public final class HmxDownloader {

    private static final String TAG = "HmxDownloader";

    private static volatile HmxDownloader instance;

    private final Context context;
    private final DownloadQueue queue;
    private final DownloadRepository repository;
    private final DownloadNotifier notifier;
    private final ExecutorService workers;
    private final Object dispatchLock = new Object();
    private final Map<String, AtomicBoolean> cancellations =
            new ConcurrentHashMap<String, AtomicBoolean>();

    private final AtomicInteger sessionSaved = new AtomicInteger();
    private final AtomicInteger sessionFailed = new AtomicInteger();
    private volatile boolean started;
    private volatile boolean shutdown;

    private HmxDownloader(Context context) {
        this.context = context.getApplicationContext();
        this.repository = new PreferencesDownloadRepository(this.context);
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
     * Loads persisted history into duplicate detection. Waiting work is not
     * resumed automatically: the user starts it explicitly.
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
        }
    }

    /**
     * Validates and enqueues an item, then starts a worker when a slot is free.
     */
    public DownloadQueue.EnqueueResult enqueue(MediaItem item) {
        start();
        DownloadQueue.EnqueueResult result;
        synchronized (dispatchLock) {
            result = queue.enqueue(item);
        }
        if (result.isAdded()) {
            dispatch();
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
     * Cancels a waiting or running task.
     *
     * @return whether the task was active
     */
    public boolean cancel(String taskId) {
        if (taskId == null) {
            return false;
        }
        AtomicBoolean flag = cancellations.get(taskId);
        if (flag != null) {
            flag.set(true);
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

    /** Manually retries a failed task when the queue policy allows it. */
    public boolean retry(String taskId) {
        if (taskId == null) {
            return false;
        }
        boolean requeued;
        synchronized (dispatchLock) {
            requeued = queue.retry(taskId);
        }
        if (requeued) {
            dispatch();
        }
        return requeued;
    }

    /** Persisted history, newest first. */
    public List<DownloadTask> history() {
        return repository.history();
    }

    /** Removes persisted history and remembered duplicate keys. */
    public void clearHistory() {
        repository.clear();
        queue.clear();
    }

    /** Visible for tests; a production process keeps the pool running. */
    public void shutdown() {
        shutdown = true;
        workers.shutdownNow();
    }

    private void dispatch() {
        synchronized (dispatchLock) {
            if (shutdown) {
                return;
            }
            for (final DownloadTask task : queue.startNext()) {
                final AtomicBoolean cancelled = new AtomicBoolean(false);
                cancellations.put(task.id(), cancelled);
                try {
                    workers.execute(new Runnable() {
                        @Override
                        public void run() {
                            runTask(task, cancelled);
                        }
                    });
                } catch (RejectedExecutionException e) {
                    cancellations.remove(task.id());
                    queue.cancel(task.id());
                    recordTerminal(task.id());
                }
            }
        }
    }

    private void runTask(DownloadTask task, AtomicBoolean cancelled) {
        DownloadResult result;
        try {
            result = DownloadWorker.execute(context, task, cancelled,
                    new DownloadWorker.ProgressListener() {
                        @Override
                        public void onProgress(long bytesRead, long totalBytes) {
                            notifier.notifyProgress(
                                    queue.runningCount() + queue.waitingCount());
                        }
                    });
        } catch (RuntimeException e) {
            Log.w(TAG, "download worker failed", e);
            result = DownloadResult.failure(DownloadError.UNKNOWN);
        }

        synchronized (dispatchLock) {
            DownloadTask current = queue.task(task.id());
            if (current != null && current.state() == DownloadState.RUNNING) {
                if (result.isSuccess()) {
                    queue.complete(task.id(), result);
                } else {
                    queue.fail(task.id(), result.error());
                }
            }
            // Otherwise the task was cancelled while the worker was busy.
            cancellations.remove(task.id());
        }
        recordTerminal(task.id());
        notifier.notifyProgress(queue.runningCount() + queue.waitingCount());
        dispatch();
    }

    private void recordTerminal(String taskId) {
        DownloadTask task = queue.task(taskId);
        if (task == null || !task.state().isTerminal()) {
            return;
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
        if (task.state() == DownloadState.DONE) {
            sessionSaved.incrementAndGet();
        } else if (task.state() == DownloadState.FAILED) {
            sessionFailed.incrementAndGet();
        }
        if (queue.runningCount() == 0 && queue.waitingCount() == 0) {
            int saved = sessionSaved.getAndSet(0);
            int failed = sessionFailed.getAndSet(0);
            notifier.notifyResult(saved, failed);
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
