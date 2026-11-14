package com.feurstagram.extension.downloader.core;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Versioned, deterministic text encoding for the download history.
 *
 * <p>The Android shell cannot store Java objects in preferences, so finished
 * tasks cross the process boundary as this format. It is deliberately plain
 * text: one header line followed by one tab-separated line per terminal task,
 * newest first. Every string field is percent-encoded, so a URL, owner name or
 * path can never inject a newline or a field separator.
 *
 * <p>Only metadata is stored. The URL query and fragment are dropped before
 * encoding, because signed CDN links carry credentials that must never reach
 * preferences. The duplicate key of an item with an id is the id, so history
 * still recognises a re-shared item after the query has been discarded.
 *
 * <p>The codec is intentionally strict: unknown versions, malformed fields,
 * duplicate task ids, non-terminal tasks and histories over the documented
 * cap are rejected rather than silently repaired. The Android repository
 * treats a rejected history as empty, so corrupt data can never crash a
 * download.
 *
 * <p>Pure-Java core: no Android imports.
 */
public final class DownloadHistoryCodec {

    /** Current format version. Bump only with a decoder for the old version. */
    public static final int VERSION = 1;

    /** Maximum number of entries kept in history. */
    public static final int MAX_ENTRIES = 100;

    /** Upper bound on an encoded history, guarding against preference abuse. */
    public static final int MAX_ENCODED_LENGTH = 512 * 1024;

    private static final String HEADER = "hmx-download-history";
    private static final String SEPARATOR = "\t";
    private static final String LINE_SEPARATOR = "\n";

    /** task + 17 fields below. */
    private static final int FIELD_COUNT = 18;

    private DownloadHistoryCodec() {
    }

    /**
     * Encodes at most {@link #MAX_ENTRIES} terminal tasks, newest first.
     *
     * @throws IllegalArgumentException when a task is still active
     */
    public static String encode(List<DownloadTask> tasks) {
        if (tasks == null) {
            throw new IllegalArgumentException("tasks must not be null");
        }
        StringBuilder out = new StringBuilder();
        out.append(HEADER).append(SEPARATOR).append(VERSION).append(LINE_SEPARATOR);
        int written = 0;
        for (DownloadTask task : tasks) {
            if (task == null) {
                throw new IllegalArgumentException("tasks must not contain null");
            }
            if (!task.isTerminal()) {
                throw new IllegalArgumentException(
                        "only terminal tasks can be encoded, found " + task.state());
            }
            if (written >= MAX_ENTRIES) {
                break;
            }
            appendTask(out, task);
            written++;
        }
        return out.toString();
    }

    /**
     * Decodes a history produced by {@link #encode(List)}.
     *
     * @throws IllegalArgumentException on any malformed, oversized or
     *         unsupported input
     */
    public static List<DownloadTask> decode(String encoded) {
        if (encoded == null) {
            throw new IllegalArgumentException("encoded history must not be null");
        }
        if (encoded.length() > MAX_ENCODED_LENGTH) {
            throw new IllegalArgumentException("encoded history is too large");
        }
        String[] lines = encoded.split(LINE_SEPARATOR, -1);
        if (lines.length == 0 || !(HEADER + SEPARATOR + VERSION).equals(lines[0])) {
            throw new IllegalArgumentException("unsupported download history header");
        }
        int last = lines.length;
        if (last > 1 && lines[last - 1].isEmpty()) {
            last--;
        }
        if (last - 1 > MAX_ENTRIES) {
            throw new IllegalArgumentException(
                    "download history contains more than " + MAX_ENTRIES + " entries");
        }
        List<DownloadTask> tasks = new ArrayList<DownloadTask>(last - 1);
        Set<String> ids = new HashSet<String>();
        for (int i = 1; i < last; i++) {
            String line = lines[i];
            if (line.isEmpty()) {
                throw new IllegalArgumentException("download history contains an empty line");
            }
            DownloadTask task = decodeTask(line);
            if (!ids.add(task.id())) {
                throw new IllegalArgumentException(
                        "download history contains a duplicate task id: " + task.id());
            }
            tasks.add(task);
        }
        return Collections.unmodifiableList(tasks);
    }

    private static void appendTask(StringBuilder out, DownloadTask task) {
        MediaItem item = task.item();
        DownloadResult result = task.result();
        DownloadError error = task.error();
        out.append("task");
        field(out, task.id());
        field(out, task.state().name());
        out.append(SEPARATOR).append(task.attempts());
        out.append(SEPARATOR).append(task.maxAttempts());
        out.append(SEPARATOR).append(task.createdAtMs());
        field(out, error == null ? "" : error.code());
        field(out, result == null ? "" : result.path());
        out.append(SEPARATOR).append(result == null ? 0L : result.bytesWritten());
        field(out, item.id());
        field(out, item.kind().name());
        field(out, item.owner());
        field(out, item.mimeType());
        out.append(SEPARATOR).append(item.sizeBytes());
        out.append(SEPARATOR).append(item.width());
        out.append(SEPARATOR).append(item.height());
        out.append(SEPARATOR).append(item.expiresAtEpochMs());
        // Drop the query and fragment: signed links carry credentials.
        field(out, MediaNaming.stripQuery(item.url()));
        out.append(LINE_SEPARATOR);
    }

    private static DownloadTask decodeTask(String line) {
        String[] parts = line.split(SEPARATOR, -1);
        if (parts.length != FIELD_COUNT) {
            throw new IllegalArgumentException(
                    "download history entry has " + parts.length
                            + " fields, expected " + FIELD_COUNT);
        }
        if (!"task".equals(parts[0])) {
            throw new IllegalArgumentException("download history entry has no task marker");
        }
        int index = 1;
        String id = unescape(parts[index++]);
        DownloadState state = parseState(unescape(parts[index++]));
        int attempts = parseInt(parts[index++], "attempts");
        int maxAttempts = parseInt(parts[index++], "maxAttempts");
        long createdAtMs = parseLong(parts[index++], "createdAtMs");
        String errorCode = unescape(parts[index++]);
        DownloadError error = errorCode.isEmpty()
                ? null
                : DownloadError.fromCode(errorCode);
        String path = unescape(parts[index++]);
        long bytes = parseLong(parts[index++], "bytesWritten");
        String itemId = unescape(parts[index++]);
        DownloadKind kind = parseKind(unescape(parts[index++]));
        String owner = unescape(parts[index++]);
        String mimeType = unescape(parts[index++]);
        long sizeBytes = parseLong(parts[index++], "sizeBytes");
        int width = parseInt(parts[index++], "width");
        int height = parseInt(parts[index++], "height");
        long expiresAt = parseLong(parts[index++], "expiresAtEpochMs");
        String url = unescape(parts[index++]);

        if (kind.isContainer()) {
            throw new IllegalArgumentException("history cannot contain a carousel container");
        }
        MediaItem item = new MediaItem.Builder(kind)
                .id(itemId)
                .url(url)
                .mimeType(mimeType)
                .sizeBytes(sizeBytes)
                .owner(owner)
                .size(width, height)
                .expiresAtEpochMs(expiresAt)
                .build();

        DownloadResult result;
        if (state == DownloadState.DONE) {
            if (path.isEmpty()) {
                throw new IllegalArgumentException("a completed history entry needs a path");
            }
            result = DownloadResult.success(path, bytes);
        } else {
            if (!path.isEmpty() || bytes != 0L) {
                throw new IllegalArgumentException(
                        "only a completed history entry can carry a result");
            }
            result = null;
        }
        return DownloadTask.restore(id, item, maxAttempts, createdAtMs, state,
                attempts, error, result);
    }

    private static DownloadState parseState(String value) {
        try {
            DownloadState state = DownloadState.valueOf(value);
            if (!state.isTerminal()) {
                throw new IllegalArgumentException(
                        "history entry is not terminal: " + value);
            }
            return state;
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("unknown history state: " + value, e);
        }
    }

    private static DownloadKind parseKind(String value) {
        try {
            return DownloadKind.valueOf(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("unknown history kind: " + value, e);
        }
    }

    private static int parseInt(String value, String name) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("invalid " + name + ": " + value, e);
        }
    }

    private static long parseLong(String value, String name) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("invalid " + name + ": " + value, e);
        }
    }

    private static void field(StringBuilder out, String value) {
        out.append(SEPARATOR).append(escape(value));
    }

    /**
     * Percent-encodes everything outside the unreserved set. The output is
     * ASCII, so a field can never contain a tab or a newline.
     */
    private static String escape(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(value.length());
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        for (byte raw : bytes) {
            int c = raw & 0xff;
            if ((c >= 'a' && c <= 'z')
                    || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9')
                    || c == '.' || c == '_' || c == '-' || c == '~') {
                out.append((char) c);
            } else {
                out.append('%');
                out.append(Character.toUpperCase(Character.forDigit(c >> 4, 16)));
                out.append(Character.toUpperCase(Character.forDigit(c & 0x0f, 16)));
            }
        }
        return out.toString();
    }

    /** Reverses {@link #escape(String)}. */
    private static String unescape(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        if (value.indexOf('%') < 0) {
            return value;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '%') {
                if (i + 2 >= value.length()) {
                    throw new IllegalArgumentException("truncated percent escape");
                }
                int high = Character.digit(value.charAt(i + 1), 16);
                int low = Character.digit(value.charAt(i + 2), 16);
                if (high < 0 || low < 0) {
                    throw new IllegalArgumentException("invalid percent escape");
                }
                out.write((high << 4) | low);
                i += 2;
            } else if (c > 0x7f) {
                throw new IllegalArgumentException("raw non-ASCII character in history");
            } else {
                out.write((byte) c);
            }
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }
}
