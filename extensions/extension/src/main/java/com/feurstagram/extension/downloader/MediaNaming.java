package com.feurstagram.extension.downloader;

import java.util.Locale;

/**
 * File names, MIME types and duplicate keys for downloaded media.
 *
 * Pure Java on purpose: the rules are unit-tested on the JVM without an
 * Android device (scripts/ci/test_downloader_logic.sh).
 */
public final class MediaNaming {

    private MediaNaming() {}

    /** The folder the downloader writes into inside the shared media collection. */
    public static final String ALBUM = "HMXInsta";

    /** Longest file name we write, so no file system rejects it. */
    public static final int MAX_NAME_LENGTH = 120;

    /**
     * A safe file name: no path separators, no control characters, no leading
     * dots, and never longer than {@link #MAX_NAME_LENGTH}. The extension is
     * kept so "photo.jpg" does not become "photo.jpg.jpg".
     */
    public static String fileName(String base, String extension) {
        String cleaned = sanitize(base);
        if (cleaned.isEmpty()) cleaned = "hmx-insta";
        String suffix = extension == null ? "" : extension.trim();
        if (!suffix.isEmpty() && !suffix.startsWith(".")) suffix = "." + suffix;
        // "photo.jpg" must not become "photo.jpg.jpg".
        if (!suffix.isEmpty() && cleaned.toLowerCase(Locale.US).endsWith(suffix.toLowerCase(Locale.US))) {
            cleaned = cleaned.substring(0, cleaned.length() - suffix.length());
        }
        int room = Math.max(1, MAX_NAME_LENGTH - suffix.length());
        if (cleaned.length() > room) cleaned = cleaned.substring(0, room);
        return cleaned + suffix;
    }

    /** Replaces anything that is not safe in a file name. */
    public static String sanitize(String value) {
        if (value == null) return "";
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '/' || c == '\\' || c == ':' || c == '*' || c == '?' || c == '"'
                    || c == '<' || c == '>' || c == '|' || c < 0x20 || c == 0x7f) {
                out.append('_');
            } else {
                out.append(c);
            }
        }
        String cleaned = out.toString().trim();
        while (cleaned.startsWith(".")) cleaned = cleaned.substring(1);
        return cleaned;
    }

    /** The MIME type the gallery expects for this kind of media. */
    public static String mimeType(MediaKind kind, boolean audioOnly) {
        if (audioOnly) return "audio/mpeg";
        if (kind == MediaKind.VIDEO) return "video/mp4";
        return "image/jpeg";
    }

    /** The file extension that goes with the MIME type. */
    public static String extension(MediaKind kind, boolean audioOnly) {
        if (audioOnly) return "mp3";
        if (kind == MediaKind.VIDEO) return "mp4";
        return "jpg";
    }

    /**
     * The key used to recognise a media item that was already downloaded.
     * Instagram's media id is stable, so it is the primary key; the URL is the
     * fallback for media whose id we could not read.
     */
    public static String duplicateKey(String mediaId, String url) {
        if (mediaId != null && !mediaId.trim().isEmpty()) {
            return "id:" + mediaId.trim();
        }
        if (url == null) return "";
        int query = url.indexOf('?');
        String base = query >= 0 ? url.substring(0, query) : url;
        return "url:" + base.toLowerCase(Locale.US);
    }
}
