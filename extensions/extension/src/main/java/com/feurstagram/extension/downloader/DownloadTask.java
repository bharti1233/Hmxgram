package com.feurstagram.extension.downloader;

/**
 * One download in flight: what it is, how far it got and how it ended.
 *
 * Pure Java on purpose: the rules are unit-tested on the JVM without an
 * Android device (scripts/ci/test_downloader_logic.sh).
 */
public final class DownloadTask {

    /** Where a task is in its life. */
    public enum State {
        QUEUED,
        RUNNING,
        DONE,
        FAILED,
        CANCELLED
    }

    private final MediaItem item;
    private final String owner;
    private final String label;
    private State state = State.QUEUED;
    private long bytesDone;
    private long bytesTotal;
    private int attempts;
    private String error;
    private String savedPath;
    private boolean cancelled;

    public DownloadTask(MediaItem item, String owner, String label) {
        this.item = item;
        this.owner = owner == null ? "" : owner;
        this.label = label == null ? item.variant().label() : label;
    }

    public MediaItem item() {
        return item;
    }

    public String owner() {
        return owner;
    }

    public String label() {
        return label;
    }

    public State state() {
        return state;
    }

    public long bytesDone() {
        return bytesDone;
    }

    public long bytesTotal() {
        return bytesTotal;
    }

    /** Progress in percent, 0 when the size is unknown. */
    public int progress() {
        if (bytesTotal <= 0) return 0;
        long value = bytesDone * 100L / bytesTotal;
        return (int) Math.max(0, Math.min(100, value));
    }

    public int attempts() {
        return attempts;
    }

    public String error() {
        return error;
    }

    public String savedPath() {
        return savedPath;
    }

    public boolean isFinished() {
        return state == State.DONE || state == State.FAILED || state == State.CANCELLED;
    }

    public boolean isCancelled() {
        return cancelled;
    }

    /** Moves the task into the running state and counts the attempt. */
    public void start(long totalBytes) {
        state = State.RUNNING;
        attempts++;
        bytesTotal = totalBytes;
        bytesDone = 0;
        error = null;
    }

    /** Records progress. */
    public void progress(long done, long total) {
        bytesDone = Math.max(0, done);
        if (total > 0) bytesTotal = total;
    }

    /** Marks the task finished and saved. */
    public void succeed(String path) {
        state = State.DONE;
        savedPath = path;
        error = null;
    }

    /** Marks the task failed with one of {@link DownloadError}'s codes. */
    public void fail(String errorCode) {
        state = State.FAILED;
        error = errorCode;
    }

    /** Marks the task cancelled. */
    public void cancel() {
        cancelled = true;
        if (state == State.RUNNING || state == State.QUEUED) {
            state = State.CANCELLED;
            error = DownloadError.CANCELLED;
        }
    }

    /** Puts a failed task back in the queue for another attempt. */
    public boolean retry() {
        if (state != State.FAILED || error == null || !DownloadError.isRecoverable(error)) {
            return false;
        }
        state = State.QUEUED;
        error = null;
        bytesDone = 0;
        return true;
    }
}
