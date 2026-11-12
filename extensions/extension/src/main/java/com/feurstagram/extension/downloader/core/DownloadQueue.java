package com.feurstagram.extension.downloader.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Bounded download queue with a concurrency limit and a retry policy.
 *
 * <p>Defaults match the documented architecture: at most 20 waiting
 * downloads, two running at a time, and three attempts for a recoverable
 * failure. A duplicate is an item already waiting, already running, or
 * already saved successfully; a failed or cancelled item may be enqueued
 * again.
 *
 * <p>The queue is thread-safe. It never performs I/O and never opens a
 * socket: the Android shell calls {@link #startNext()}, downloads, then calls
 * {@link #complete(String, DownloadResult)} or
 * {@link #fail(String, DownloadError)}.
 *
 * <p>Pure-Java core: no Android imports.
 */
public final class DownloadQueue {

    public static final int DEFAULT_MAX_WAITING = 20;
    public static final int DEFAULT_MAX_CONCURRENT = 2;
    public static final int DEFAULT_MAX_ATTEMPTS = DownloadTask.DEFAULT_MAX_ATTEMPTS;

    /**
     * How many finished tasks stay addressable through {@link #task(String)}.
     * Older finished tasks are pruned; successful duplicate keys survive in
     * the separate saved-key set until its own capacity is reached.
     */
    public static final int MAX_FINISHED_TASKS = 100;

    /** The outcome of {@link #enqueue(MediaItem)}. */
    public enum EnqueueStatus {
        /** The task was added to the waiting list. */
        ADDED,
        /** The item is already waiting, running or saved. */
        DUPLICATE,
        /** The waiting list is full. */
        FULL
    }

    /** The result of {@link #enqueue(MediaItem)}. */
    public static final class EnqueueResult {

        private final EnqueueStatus status;
        private final DownloadTask task;

        private EnqueueResult(EnqueueStatus status, DownloadTask task) {
            this.status = status;
            this.task = task;
        }

        public EnqueueStatus status() {
            return status;
        }

        /** The added task, or {@code null} for duplicate/full results. */
        public DownloadTask task() {
            return task;
        }

        public boolean isAdded() {
            return status == EnqueueStatus.ADDED;
        }
    }

    private final int maxWaiting;
    private final int maxConcurrent;
    private final int maxAttempts;
    private final DuplicateDetector saved = new DuplicateDetector();
    private final Map<String, DownloadTask> tasks = new LinkedHashMap<String, DownloadTask>();
    private final ArrayDeque<DownloadTask> waiting = new ArrayDeque<DownloadTask>();
    private final List<DownloadTask> running = new ArrayList<DownloadTask>();
    private long sequence;

    public DownloadQueue() {
        this(DEFAULT_MAX_WAITING, DEFAULT_MAX_CONCURRENT, DEFAULT_MAX_ATTEMPTS);
    }

    public DownloadQueue(int maxWaiting, int maxConcurrent, int maxAttempts) {
        if (maxWaiting <= 0) {
            throw new IllegalArgumentException("maxWaiting must be positive");
        }
        if (maxConcurrent <= 0) {
            throw new IllegalArgumentException("maxConcurrent must be positive");
        }
        if (maxAttempts <= 0) {
            throw new IllegalArgumentException("maxAttempts must be positive");
        }
        this.maxWaiting = maxWaiting;
        this.maxConcurrent = maxConcurrent;
        this.maxAttempts = maxAttempts;
    }

    /**
     * Validates and enqueues an item.
     *
     * @throws IllegalArgumentException when the item is null or cannot be
     *         downloaded at {@code nowMs} (for example an expired story)
     */
    public synchronized EnqueueResult enqueue(MediaItem item) {
        return enqueue(item, System.currentTimeMillis());
    }

    /** Enqueues an item using {@code nowMs} for the expiry rule. */
    public synchronized EnqueueResult enqueue(MediaItem item, long nowMs) {
        if (item == null) {
            throw new IllegalArgumentException("item must not be null");
        }
        if (item.kind().isContainer()) {
            throw new IllegalArgumentException(
                    "expand a carousel before enqueueing its children");
        }
        DownloadError problem = item.downloadability(nowMs);
        if (problem != null) {
            throw new IllegalArgumentException(
                    "item is not downloadable: " + problem.code());
        }
        String key = MediaNaming.duplicateKey(item);
        if (saved.isSeen(key) || isActive(key)) {
            return new EnqueueResult(EnqueueStatus.DUPLICATE, null);
        }
        if (waiting.size() >= maxWaiting) {
            return new EnqueueResult(EnqueueStatus.FULL, null);
        }
        DownloadTask task = new DownloadTask(
                "task-" + (++sequence), item, maxAttempts, nowMs);
        tasks.put(task.id(), task);
        waiting.addLast(task);
        return new EnqueueResult(EnqueueStatus.ADDED, task);
    }

    /**
     * Moves as many waiting tasks to RUNNING as the concurrency limit allows.
     *
     * @return the tasks that were started, in queue order
     */
    public synchronized List<DownloadTask> startNext() {
        List<DownloadTask> started = new ArrayList<DownloadTask>();
        while (running.size() < maxConcurrent && !waiting.isEmpty()) {
            DownloadTask task = waiting.removeFirst();
            task.start();
            running.add(task);
            started.add(task);
        }
        return Collections.unmodifiableList(started);
    }

    /**
     * Completes a running task successfully.
     *
     * @throws IllegalArgumentException when the task is not running
     */
    public synchronized void complete(String taskId, DownloadResult result) {
        DownloadTask task = requireRunning(taskId);
        task.succeed(result);
        running.remove(task);
        saved.remember(task.duplicateKey());
        pruneFinished();
    }

    /**
     * Fails a running task. When the error is recoverable, an attempt remains
     * and the waiting list has room, the task is put back at the end of the
     * queue.
     *
     * @return whether the task was requeued
     * @throws IllegalArgumentException when the task is not running
     */
    public synchronized boolean fail(String taskId, DownloadError error) {
        DownloadTask task = requireRunning(taskId);
        task.fail(error);
        running.remove(task);
        if (task.canRetry() && waiting.size() < maxWaiting && task.retry()) {
            waiting.addLast(task);
            return true;
        }
        pruneFinished();
        return false;
    }

    /**
     * Cancels a waiting or running task.
     *
     * @return whether the task was active
     */
    public synchronized boolean cancel(String taskId) {
        DownloadTask task = tasks.get(taskId);
        if (task == null || !task.state().isActive()) {
            return false;
        }
        if (task.state() == DownloadState.RUNNING) {
            running.remove(task);
        } else {
            waiting.remove(task);
        }
        task.cancel();
        pruneFinished();
        return true;
    }

    /**
     * Manually retries a failed task when its error is recoverable and it has
     * attempts left. Refused when the same item is already waiting, running or
     * saved, so a retry can never create a second active copy.
     *
     * @return whether the task was requeued
     */
    public synchronized boolean retry(String taskId) {
        DownloadTask task = tasks.get(taskId);
        if (task == null || waiting.size() >= maxWaiting || !task.canRetry()) {
            return false;
        }
        String key = task.duplicateKey();
        if (saved.isSeen(key) || isActive(key)) {
            return false;
        }
        boolean requeued = task.retry();
        if (requeued) {
            waiting.addLast(task);
        }
        return requeued;
    }

    /** Looks up a task by id, including finished tasks. */
    public synchronized DownloadTask task(String taskId) {
        return tasks.get(taskId);
    }

    /** Immutable snapshot of the waiting list. */
    public synchronized List<DownloadTask> waiting() {
        return Collections.unmodifiableList(new ArrayList<DownloadTask>(waiting));
    }

    /** Immutable snapshot of the running list. */
    public synchronized List<DownloadTask> running() {
        return Collections.unmodifiableList(new ArrayList<DownloadTask>(running));
    }

    /** Immutable snapshot of every task known to this queue. */
    public synchronized List<DownloadTask> tasks() {
        return Collections.unmodifiableList(new ArrayList<DownloadTask>(tasks.values()));
    }

    public synchronized int waitingCount() {
        return waiting.size();
    }

    public synchronized int runningCount() {
        return running.size();
    }

    /** Whether the item is already waiting, running or saved. */
    public synchronized boolean isDuplicate(MediaItem item) {
        String key = MediaNaming.duplicateKey(item);
        return saved.isSeen(key) || isActive(key);
    }

    /** The number of successfully saved items remembered for duplicate checks. */
    public synchronized int savedCount() {
        return saved.size();
    }

    /**
     * Remembers an already-saved duplicate key, for example one restored from
     * persisted download history at startup.
     *
     * @return whether the key was new
     */
    public synchronized boolean rememberSaved(String duplicateKey) {
        return saved.remember(duplicateKey);
    }

    /** Remembers an already-saved item for duplicate checks. */
    public synchronized boolean rememberSaved(MediaItem item) {
        if (item == null) {
            throw new IllegalArgumentException("item must not be null");
        }
        return saved.remember(item);
    }

    /**
     * Seeds duplicate detection from the successfully completed tasks in a
     * repository restored after a restart. Failed and cancelled entries are
     * ignored.
     *
     * @return the number of new saved keys remembered
     */
    public synchronized int seedSaved(DownloadRepository repository) {
        if (repository == null) {
            throw new IllegalArgumentException("repository must not be null");
        }
        int seeded = 0;
        for (DownloadTask task : repository.history()) {
            if (task.state() == DownloadState.DONE
                    && saved.remember(task.duplicateKey())) {
                seeded++;
            }
        }
        return seeded;
    }

    public int maxWaiting() {
        return maxWaiting;
    }

    public int maxConcurrent() {
        return maxConcurrent;
    }

    public int maxAttempts() {
        return maxAttempts;
    }

    /**
     * Forgets every finished task and the duplicate history. Waiting and
     * running tasks are kept, so clearing history never loses an in-flight
     * download; task ids also keep increasing.
     */
    public synchronized void clear() {
        Iterator<Map.Entry<String, DownloadTask>> iterator = tasks.entrySet().iterator();
        while (iterator.hasNext()) {
            if (iterator.next().getValue().state().isTerminal()) {
                iterator.remove();
            }
        }
        saved.clear();
    }

    private boolean isActive(String key) {
        for (DownloadTask task : tasks.values()) {
            if (task.state().isActive() && task.duplicateKey().equals(key)) {
                return true;
            }
        }
        return false;
    }

    /** Drops the oldest finished tasks while keeping the map bounded. */
    private void pruneFinished() {
        int finished = 0;
        for (DownloadTask task : tasks.values()) {
            if (task.state().isTerminal()) {
                finished++;
            }
        }
        if (finished <= MAX_FINISHED_TASKS) {
            return;
        }
        Iterator<Map.Entry<String, DownloadTask>> iterator = tasks.entrySet().iterator();
        while (finished > MAX_FINISHED_TASKS && iterator.hasNext()) {
            if (iterator.next().getValue().state().isTerminal()) {
                iterator.remove();
                finished--;
            }
        }
    }

    private DownloadTask requireRunning(String taskId) {
        DownloadTask task = tasks.get(taskId);
        if (task == null || task.state() != DownloadState.RUNNING) {
            throw new IllegalArgumentException("task is not running: " + taskId);
        }
        return task;
    }
}
