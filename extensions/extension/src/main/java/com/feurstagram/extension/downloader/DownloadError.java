package com.feurstagram.extension.downloader;

/**
 * The failures the downloader can report, and whether a retry could help.
 *
 * Pure Java on purpose: the rules are unit-tested on the JVM without an
 * Android device (scripts/ci/test_downloader_logic.sh).
 */
public final class DownloadError {

    /** No network or the server refused the request. A retry may work. */
    public static final String NETWORK = "network";
    /** The media URL is no longer valid (expired CDN signature). */
    public static final String EXPIRED = "expired";
    /** The media could not be written to the gallery. */
    public static final String STORAGE = "storage";
    /** The download was cancelled by the user. */
    public static final String CANCELLED = "cancelled";
    /** The media is not downloadable (no URL, wrong type, not permitted). */
    public static final String UNSUPPORTED = "unsupported";
    /** The media was already downloaded. */
    public static final String DUPLICATE = "duplicate";
    /** The server answered with an error status. */
    public static final String SERVER = "server";

    private DownloadError() {}

    /** Whether retrying the same URL could succeed. */
    public static boolean isRecoverable(String error) {
        return NETWORK.equals(error) || SERVER.equals(error);
    }

    /** A message a user can read, without any URL or identifier in it. */
    public static String message(String error) {
        if (error == null) return "Download failed";
        switch (error) {
            case NETWORK:
                return "The network is unavailable. Tap to retry.";
            case EXPIRED:
                return "This media link has expired. Open the post again and retry.";
            case STORAGE:
                return "The file could not be saved to your gallery.";
            case CANCELLED:
                return "Download cancelled";
            case UNSUPPORTED:
                return "This media cannot be downloaded.";
            case DUPLICATE:
                return "This media was already downloaded.";
            case SERVER:
                return "The server refused the download. Tap to retry.";
            default:
                return "Download failed";
        }
    }
}
