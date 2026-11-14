package com.feurstagram.extension.downloader.core;

/**
 * One line of the downloader screen, projected from a {@link DownloadTask}.
 *
 * <p>The screen must never invent state: a row is built from the task the
 * engine actually holds, plus the byte counters the worker reported. A row
 * that says "saved" therefore means the engine finished the task with a
 * result that carries a saved path; a task without that is reported as
 * unverified instead of as a success.
 *
 * <p>The projection is pure Java so the rules can be unit-tested without an
 * Android runtime: no Android imports.
 */
public final class DownloaderRow {

    /** The source did not announce a size, so the percentage is unknown. */
    public static final long UNKNOWN_BYTES = -1L;

    /** The percentage is unknown when the total size is unknown. */
    public static final int UNKNOWN_PERCENT = -1;

    private final String id;
    private final DownloadKind kind;
    private final DownloadState state;
    private final String title;
    private final String location;
    private final long bytesRead;
    private final long totalBytes;
    private final DownloadError error;
    private final String savedPath;
    private final long createdAtMs;
    private final int attempts;
    private final int maxAttempts;
    private final String owner;
    private final String sourceUrl;

    private DownloaderRow(String id, DownloadKind kind, DownloadState state,
            String title, String location, long bytesRead, long totalBytes,
            DownloadError error, String savedPath, long createdAtMs,
            int attempts, int maxAttempts, String owner, String sourceUrl) {
        this.id = id;
        this.kind = kind;
        this.state = state;
        this.title = title;
        this.location = location;
        this.bytesRead = bytesRead;
        this.totalBytes = totalBytes;
        this.error = error;
        this.savedPath = savedPath;
        this.createdAtMs = createdAtMs;
        this.attempts = attempts;
        this.maxAttempts = maxAttempts;
        this.owner = owner == null ? "" : owner;
        this.sourceUrl = sourceUrl == null ? "" : sourceUrl;
    }

    /**
     * Projects a task.
     *
     * @param task the task the engine holds
     * @param bytesRead bytes written so far, {@code 0} when nothing was read
     * @param totalBytes announced total size, {@link #UNKNOWN_BYTES} when the
     *        source did not announce one
     */
    public static DownloaderRow from(DownloadTask task, long bytesRead,
            long totalBytes) {
        if (task == null) {
            throw new IllegalArgumentException("task must not be null");
        }
        MediaItem item = task.item();
        DownloadResult result = task.result();
        String savedPath = null;
        if (result != null && result.isSuccess() && result.path() != null
                && !result.path().trim().isEmpty()) {
            savedPath = result.path();
        }
        long total = totalBytes > 0L ? totalBytes : UNKNOWN_BYTES;
        return new DownloaderRow(
                task.id(),
                item.kind(),
                task.state(),
                title(item, task.createdAtMs()),
                location(item),
                Math.max(0L, bytesRead),
                total,
                task.error(),
                savedPath,
                task.createdAtMs(),
                task.attempts(),
                task.maxAttempts(),
                item.owner(),
                item.url());
    }

    private static String title(MediaItem item, long createdAtMs) {
        try {
            String name = MediaNaming.buildFileName(item, createdAtMs);
            if (name != null && !name.trim().isEmpty()) {
                return name;
            }
        } catch (RuntimeException e) {
            // A name that cannot be built must not hide the row.
        }
        return item.id();
    }

    private static String location(MediaItem item) {
        try {
            return StoragePlan.forItem(item, System.currentTimeMillis())
                    .relativeDirectory();
        } catch (RuntimeException e) {
            return "";
        }
    }

    public String id() {
        return id;
    }

    public DownloadKind kind() {
        return kind;
    }

    public DownloadState state() {
        return state;
    }

    /** The file name the download will use. */
    public String title() {
        return title;
    }

    /** The gallery directory the item is published into. */
    public String location() {
        return location;
    }

    public long bytesRead() {
        return bytesRead;
    }

    /** The announced total size, or {@link #UNKNOWN_BYTES}. */
    public long totalBytes() {
        return totalBytes;
    }

    /** The failure, or {@code null} when the task did not fail. */
    public DownloadError error() {
        return error;
    }

    /** The published path, or {@code null} when nothing was saved. */
    public String savedPath() {
        return savedPath;
    }

    public long createdAtMs() {
        return createdAtMs;
    }

    public int attempts() {
        return attempts;
    }

    public int maxAttempts() {
        return maxAttempts;
    }

    /** The original Instagram username, or an empty string when unknown. */
    public String owner() {
        return owner;
    }

    /** The URL the item is downloaded from, or an empty string. */
    public String sourceUrl() {
        return sourceUrl;
    }

    /** A short, human name for the media type. */
    public String kindLabel() {
        switch (kind) {
            case PHOTO:
                return "Photo";
            case VIDEO:
                return "Video";
            case AUDIO:
                return "Audio";
            case COVER:
                return "Cover";
            case CAROUSEL:
                return "Carousel";
            default:
                return kind == null ? "Media" : kind.name();
        }
    }

    /**
     * The completion percentage, or {@link #UNKNOWN_PERCENT} when the source
     * did not announce a size. The value is always between 0 and 100.
     */
    public int progressPercent() {
        if (totalBytes <= 0L) {
            return UNKNOWN_PERCENT;
        }
        long percent = bytesRead * 100L / totalBytes;
        if (percent < 0L) {
            return 0;
        }
        if (percent > 100L) {
            return 100;
        }
        return (int) percent;
    }

    /** Whether the task is still waiting or running. */
    public boolean isActive() {
        return state.isActive();
    }

    /**
     * Whether the file was really saved: the task finished and its result
     * carries the path the gallery row was published under.
     */
    public boolean isVerified() {
        return state == DownloadState.DONE && savedPath != null;
    }

    /** Whether the user may cancel the task now. */
    public boolean canCancel() {
        return state.isActive();
    }

    /**
     * Whether the user may retry the task: it failed with a recoverable
     * error and still has attempts left.
     */
    public boolean canRetry() {
        return state == DownloadState.FAILED
                && error != null
                && error.isRecoverable()
                && attempts < maxAttempts;
    }

    /** Whether the user may remove the entry from the history. */
    public boolean canDelete() {
        return state.isTerminal();
    }

    /**
     * A short, honest status line. A finished task is only reported as saved
     * when {@link #isVerified()} holds.
     */
    public String statusLabel() {
        switch (state) {
            case QUEUED:
                return "Queued";
            case RUNNING:
                return progressPercent() >= 0
                        ? "Downloading — " + progressPercent() + "%"
                        : "Downloading — " + formatBytes(bytesRead);
            case DONE:
                return isVerified() ? "Saved" : "Finished (not verified)";
            case FAILED:
                return error == null ? "Failed" : error.message();
            case CANCELLED:
                return "Cancelled";
            default:
                return state.name();
        }
    }

    /** A human-readable byte count, for example {@code 1.4 MB}. */
    public static String formatBytes(long bytes) {
        if (bytes < 0L) {
            return "0 B";
        }
        if (bytes < 1024L) {
            return bytes + " B";
        }
        String[] units = {"KB", "MB", "GB", "TB"};
        double value = bytes / 1024.0;
        int unit = 0;
        while (value >= 1024.0 && unit < units.length - 1) {
            value = value / 1024.0;
            unit++;
        }
        String formatted = String.format(java.util.Locale.ROOT, "%.1f", value);
        return formatted + " " + units[unit];
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof DownloaderRow)) {
            return false;
        }
        DownloaderRow that = (DownloaderRow) other;
        return id.equals(that.id)
                && state == that.state
                && bytesRead == that.bytesRead
                && totalBytes == that.totalBytes
                && (savedPath == null ? that.savedPath == null
                        : savedPath.equals(that.savedPath));
    }

    @Override
    public int hashCode() {
        int hash = id.hashCode();
        hash = 31 * hash + state.hashCode();
        hash = 31 * hash + (int) (bytesRead ^ (bytesRead >>> 32));
        hash = 31 * hash + (int) (totalBytes ^ (totalBytes >>> 32));
        hash = 31 * hash + (savedPath == null ? 0 : savedPath.hashCode());
        return hash;
    }

    @Override
    public String toString() {
        return "DownloaderRow{" + id + ", " + state + ", " + statusLabel() + "}";
    }
}
