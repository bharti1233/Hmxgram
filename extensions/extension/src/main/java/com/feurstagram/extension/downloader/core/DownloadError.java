package com.feurstagram.extension.downloader.core;

/**
 * The downloader's error taxonomy.
 *
 * <p>An error is <strong>recoverable</strong> when repeating the same request
 * later can plausibly succeed (a transient network fault or a server error).
 * Expired CDN links, storage failures, invalid media and cancellation are not
 * retried automatically or manually.
 *
 * <p>Pure-Java core: no Android imports.
 */
public enum DownloadError {

    /** The connection failed or was interrupted. */
    NETWORK(true, "network", "Network error while downloading"),

    /** The connection or read timed out. */
    TIMEOUT(true, "timeout", "The download timed out"),

    /** The server returned an error response. */
    SERVER(true, "server", "The server rejected the download"),

    /** Instagram's signed CDN link is no longer valid. */
    EXPIRED_LINK(false, "expired-link", "This media link has expired"),

    /** A story is past its 24-hour lifetime. */
    EXPIRED_MEDIA(false, "expired-media", "This story has expired"),

    /** The file could not be written to the gallery. */
    STORAGE(false, "storage", "Could not save the media to storage"),

    /** The media model did not contain a usable URL or child list. */
    INVALID_MEDIA(false, "invalid-media", "This media cannot be downloaded"),

    /** The media kind is not supported by this downloader. */
    UNSUPPORTED(false, "unsupported", "This media type is not supported"),

    /** The user cancelled the download. */
    CANCELLED(false, "cancelled", "Download cancelled"),

    /** An error that did not fit any other category. */
    UNKNOWN(false, "unknown", "The download failed");

    private final boolean recoverable;
    private final String code;
    private final String message;

    DownloadError(boolean recoverable, String code, String message) {
        this.recoverable = recoverable;
        this.code = code;
        this.message = message;
    }

    /** Whether retrying later is allowed. */
    public boolean isRecoverable() {
        return recoverable;
    }

    /** Stable machine-readable code, safe for logs and preferences. */
    public String code() {
        return code;
    }

    /** Human-readable, user-facing message. */
    public String message() {
        return message;
    }

    /** Resolves a code produced by {@link #code()}. */
    public static DownloadError fromCode(String code) {
        if (code == null) {
            throw new IllegalArgumentException("code must not be null");
        }
        for (DownloadError error : values()) {
            if (error.code.equals(code)) {
                return error;
            }
        }
        throw new IllegalArgumentException("Unknown download error code: " + code);
    }
}
