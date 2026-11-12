package com.feurstagram.extension.downloader.core;

import java.util.Objects;

/**
 * The outcome of one download attempt.
 *
 * <p>A successful result carries the gallery path written by the Android
 * shell; a failed result carries one {@link DownloadError}. The core never
 * touches the filesystem, so the path is opaque here.
 *
 * <p>Pure-Java core: no Android imports.
 */
public final class DownloadResult {

    private final DownloadError error;
    private final String path;
    private final long bytesWritten;

    private DownloadResult(DownloadError error, String path, long bytesWritten) {
        this.error = error;
        this.path = path;
        this.bytesWritten = bytesWritten;
    }

    /** Creates a successful result. */
    public static DownloadResult success(String path, long bytesWritten) {
        if (path == null || path.trim().isEmpty()) {
            throw new IllegalArgumentException("a successful result needs a path");
        }
        if (bytesWritten < 0) {
            throw new IllegalArgumentException("bytesWritten must not be negative");
        }
        return new DownloadResult(null, path, bytesWritten);
    }

    /** Creates a failed result. */
    public static DownloadResult failure(DownloadError error) {
        if (error == null) {
            throw new IllegalArgumentException("error must not be null");
        }
        if (error == DownloadError.CANCELLED) {
            throw new IllegalArgumentException("use cancelled() for cancellation");
        }
        return new DownloadResult(error, null, 0L);
    }

    /** Creates a cancelled result. */
    public static DownloadResult cancelled() {
        return new DownloadResult(DownloadError.CANCELLED, null, 0L);
    }

    public boolean isSuccess() {
        return error == null;
    }

    /** The error, or {@code null} on success. */
    public DownloadError error() {
        return error;
    }

    /** The gallery path, or {@code null} when the download did not complete. */
    public String path() {
        return path;
    }

    /** Bytes written, or {@code 0} when nothing was written. */
    public long bytesWritten() {
        return bytesWritten;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof DownloadResult)) {
            return false;
        }
        DownloadResult that = (DownloadResult) other;
        return bytesWritten == that.bytesWritten
                && error == that.error
                && Objects.equals(path, that.path);
    }

    @Override
    public int hashCode() {
        return Objects.hash(error, path, bytesWritten);
    }

    @Override
    public String toString() {
        if (isSuccess()) {
            return "DownloadResult{success, path=" + path
                    + ", bytes=" + bytesWritten + '}';
        }
        return "DownloadResult{" + error.code() + '}';
    }
}
