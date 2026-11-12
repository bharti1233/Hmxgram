package com.feurstagram.extension.downloader.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * File naming, MIME/extension mapping and duplicate keys.
 *
 * <p>Every name produced here is a single path segment: no separator, no
 * {@code ..}, no control character and no character that is illegal on
 * Android's emulated storage. The caller supplies the directory.
 *
 * <p>Pure-Java core: no Android imports.
 */
public final class MediaNaming {

    /** Longest file name accepted by MediaStore in practice. */
    public static final int MAX_FILE_NAME_LENGTH = 120;

    private static final int MAX_SEGMENT_LENGTH = 48;
    private static final Map<String, String> MIME_TO_EXTENSION;
    private static final Map<String, String> EXTENSION_TO_MIME;
    private static final Set<String> RESERVED_NAMES;

    static {
        Map<String, String> mime = new HashMap<String, String>();
        mime.put("image/jpeg", "jpg");
        mime.put("image/jpg", "jpg");
        mime.put("image/pjpeg", "jpg");
        mime.put("image/png", "png");
        mime.put("image/webp", "webp");
        mime.put("image/heic", "heic");
        mime.put("image/heif", "heif");
        mime.put("image/gif", "gif");
        mime.put("image/avif", "avif");
        mime.put("video/mp4", "mp4");
        mime.put("video/quicktime", "mov");
        mime.put("video/webm", "webm");
        mime.put("video/x-matroska", "mkv");
        mime.put("audio/mp4", "m4a");
        mime.put("audio/m4a", "m4a");
        mime.put("audio/aac", "m4a");
        mime.put("audio/mpeg", "mp3");
        mime.put("audio/ogg", "ogg");
        mime.put("audio/opus", "opus");
        MIME_TO_EXTENSION = Collections.unmodifiableMap(mime);

        Map<String, String> extension = new HashMap<String, String>();
        for (Map.Entry<String, String> entry : mime.entrySet()) {
            if (!extension.containsKey(entry.getValue())) {
                extension.put(entry.getValue(), entry.getKey());
            }
        }
        // Canonical reverse mappings, independent of HashMap iteration order.
        extension.put("jpg", "image/jpeg");
        extension.put("m4a", "audio/mp4");
        EXTENSION_TO_MIME = Collections.unmodifiableMap(extension);

        Set<String> reserved = new HashSet<String>();
        Collections.addAll(reserved, ".", "..", "con", "prn", "aux", "nul",
                "com1", "com2", "com3", "com4", "com5", "com6", "com7", "com8",
                "com9", "lpt1", "lpt2", "lpt3", "lpt4", "lpt5", "lpt6", "lpt7",
                "lpt8", "lpt9");
        RESERVED_NAMES = Collections.unmodifiableSet(reserved);
    }

    private MediaNaming() {
    }

    /**
     * Turns arbitrary text into one safe path segment. Anything outside
     * {@code [A-Za-z0-9._-]} becomes {@code _}; repeated separators collapse;
     * leading/trailing dots, dashes and underscores are stripped; reserved
     * device names and empty results fall back to {@code media}.
     */
    public static String sanitizeSegment(String raw) {
        if (raw == null) {
            return "media";
        }
        StringBuilder out = new StringBuilder(raw.length());
        boolean lastWasSeparator = false;
        for (int i = 0; i < raw.length() && out.length() < MAX_SEGMENT_LENGTH; i++) {
            char c = raw.charAt(i);
            boolean safe = (c >= 'a' && c <= 'z')
                    || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9')
                    || c == '.' || c == '_' || c == '-';
            if (safe) {
                out.append(c);
                lastWasSeparator = false;
            } else if (!lastWasSeparator) {
                out.append('_');
                lastWasSeparator = true;
            }
        }
        String value = trimSeparators(out.toString());
        if (value.isEmpty() || RESERVED_NAMES.contains(value.toLowerCase(Locale.ROOT))) {
            return "media";
        }
        return value;
    }

    private static String trimSeparators(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && (value.charAt(start) == '.' || value.charAt(start) == '-'
                || value.charAt(start) == '_')) {
            start++;
        }
        while (end > start && (value.charAt(end - 1) == '.' || value.charAt(end - 1) == '-'
                || value.charAt(end - 1) == '_')) {
            end--;
        }
        return value.substring(start, end);
    }

    /** Extension (without the dot) for a MIME type, or {@code bin}. */
    public static String extensionForMime(String mimeType) {
        if (mimeType == null) {
            return "bin";
        }
        String normalized = mimeType.trim().toLowerCase(Locale.ROOT);
        int parameter = normalized.indexOf(';');
        if (parameter >= 0) {
            normalized = normalized.substring(0, parameter).trim();
        }
        String extension = MIME_TO_EXTENSION.get(normalized);
        return extension == null ? "bin" : extension;
    }

    /** MIME type for an extension (with or without the dot), or {@code null}. */
    public static String mimeForExtension(String extension) {
        if (extension == null) {
            return null;
        }
        String normalized = extension.trim().toLowerCase(Locale.ROOT);
        if (normalized.startsWith(".")) {
            normalized = normalized.substring(1);
        }
        return EXTENSION_TO_MIME.get(normalized);
    }

    /** The extension for an item, using its MIME type first and its kind second. */
    public static String extensionFor(MediaItem item) {
        if (item == null) {
            throw new IllegalArgumentException("item must not be null");
        }
        String extension = extensionForMime(item.mimeType());
        if (!"bin".equals(extension)) {
            return extension;
        }
        switch (item.kind()) {
            case PHOTO:
            case COVER:
                return "jpg";
            case VIDEO:
                return "mp4";
            case AUDIO:
                return "m4a";
            default:
                return "bin";
        }
    }

    /** The MIME type for an item, using its declared type first. */
    public static String mimeFor(MediaItem item) {
        if (item == null) {
            throw new IllegalArgumentException("item must not be null");
        }
        String declared = item.mimeType().trim();
        if (!declared.isEmpty()) {
            int parameter = declared.indexOf(';');
            if (parameter >= 0) {
                declared = declared.substring(0, parameter).trim();
            }
            if (!declared.isEmpty()) {
                return declared.toLowerCase(Locale.ROOT);
            }
        }
        String extension = extensionFor(item);
        String mime = mimeForExtension(extension);
        if (mime != null) {
            return mime;
        }
        switch (item.kind()) {
            case PHOTO:
            case COVER:
                return "image/jpeg";
            case VIDEO:
                return "video/mp4";
            case AUDIO:
                return "audio/mp4";
            default:
                return "application/octet-stream";
        }
    }

    /**
     * Builds a deterministic, safe file name:
     * {@code hmx_<owner>_<id>_<kind>_<timestamp>.<ext>}. Missing metadata is
     * replaced by a stable fallback; a missing id becomes a short hash of the
     * duplicate key. An id that sanitisation had to change also gets that
     * short hash appended, so two different media ids cannot collapse to the
     * same file name.
     */
    public static String buildFileName(MediaItem item, long timestampMs) {
        if (item == null) {
            throw new IllegalArgumentException("item must not be null");
        }
        if (item.kind().isContainer()) {
            throw new IllegalArgumentException("a carousel container is not a file");
        }
        if (timestampMs < 0L) {
            throw new IllegalArgumentException("timestamp must not be negative");
        }
        String key = duplicateKey(item);
        String owner = sanitizeSegment(item.owner());
        String rawId = item.id().trim();
        String id;
        if (rawId.isEmpty()) {
            id = shortHash(key);
        } else {
            String safeId = sanitizeSegment(rawId);
            id = safeId.equals(rawId) ? safeId : safeId + '_' + shortHash(key);
        }
        String kind = item.kind().name().toLowerCase(Locale.ROOT);
        String extension = extensionFor(item);
        String name = "hmx_" + owner + '_' + id + '_' + kind + '_' + timestampMs
                + '.' + extension;
        if (name.length() > MAX_FILE_NAME_LENGTH) {
            int suffixLength = ("_" + kind + '_' + timestampMs + '.' + extension).length();
            int ownerBudget = Math.max(1, MAX_FILE_NAME_LENGTH - suffixLength
                    - 17 /* "hmx_" + "_" + 12-char hash */);
            String shortOwner = owner.length() > ownerBudget
                    ? owner.substring(0, ownerBudget)
                    : owner;
            name = "hmx_" + shortOwner + '_' + shortHash(key) + '_'
                    + kind + '_' + timestampMs + '.' + extension;
        }
        return name;
    }

    /**
     * The key used to detect duplicates: the media id when present, otherwise
     * the URL without its query string or fragment. A carousel without an id
     * combines its children's keys.
     */
    public static String duplicateKey(MediaItem item) {
        if (item == null) {
            throw new IllegalArgumentException("item must not be null");
        }
        String id = item.id().trim();
        if (!id.isEmpty()) {
            return "id:" + id;
        }
        if (item.kind().isContainer()) {
            StringBuilder key = new StringBuilder("carousel:");
            for (MediaItem child : item.children()) {
                if (key.length() > "carousel:".length()) {
                    key.append('|');
                }
                key.append(duplicateKey(child));
            }
            return key.toString();
        }
        return "url:" + stripQuery(item.url());
    }

    /** Removes the query string and fragment from a URL. */
    public static String stripQuery(String url) {
        if (url == null) {
            return "";
        }
        String value = url.trim();
        int cut = value.length();
        int query = value.indexOf('?');
        if (query >= 0) {
            cut = query;
        }
        int fragment = value.indexOf('#');
        if (fragment >= 0 && fragment < cut) {
            cut = fragment;
        }
        return value.substring(0, cut);
    }

    /** First 12 hex characters of the SHA-256 of {@code value}. */
    public static String shortHash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(
                    (value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(12);
            for (int i = 0; i < bytes.length && hex.length() < 12; i++) {
                hex.append(String.format(Locale.ROOT, "%02x", bytes[i] & 0xff));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandatory on every Java and Android runtime.
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
