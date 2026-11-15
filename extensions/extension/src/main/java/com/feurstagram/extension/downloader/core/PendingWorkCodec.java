package com.feurstagram.extension.downloader.core;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Versioned text encoding for work that has been accepted but not finished.
 *
 * <p>The download history only stores terminal tasks. A process kill during a
 * download would therefore lose the queue silently, so the accepted items are
 * mirrored into a separate, small ledger before dispatch and removed when the
 * task reaches a terminal state. On the next start the ledger is replayed
 * through the normal queue, where duplicate detection prevents a second copy.
 *
 * <p>Unlike the history codec, this ledger keeps the full URL: an accepted
 * {@code content://} share has no credentials, and an HTTP link must stay
 * usable to resume. It is never shown in the UI and is removed on completion.
 *
 * <p>The format is deliberately strict: a corrupt ledger is rejected and
 * treated as empty by the Android layer, so it can never crash a download.
 *
 * <p>Pure-Java core: no Android imports.
 */
public final class PendingWorkCodec {

    /** Current format version. Bump only with a decoder for the old version. */
    public static final int VERSION = 1;

    /** At most one full waiting list, matching the queue default. */
    public static final int MAX_ENTRIES = 20;

    /** Upper bound on an encoded ledger. */
    public static final int MAX_ENCODED_LENGTH = 256 * 1024;

    private static final String HEADER = "hmx-pending-work";
    private static final String SEPARATOR = "\t";
    private static final String LINE_SEPARATOR = "\n";

    /** marker + 9 fields. */
    private static final int FIELD_COUNT = 10;

    private PendingWorkCodec() {
    }

    /**
     * Encodes at most {@link #MAX_ENTRIES} accepted items, oldest first.
     *
     * @throws IllegalArgumentException for a null or carousel item
     */
    public static String encode(List<MediaItem> items) {
        if (items == null) {
            throw new IllegalArgumentException("items must not be null");
        }
        StringBuilder out = new StringBuilder();
        out.append(HEADER).append(SEPARATOR).append(VERSION).append(LINE_SEPARATOR);
        int written = 0;
        for (MediaItem item : items) {
            if (item == null) {
                throw new IllegalArgumentException("items must not contain null");
            }
            if (item.kind().isContainer()) {
                throw new IllegalArgumentException(
                        "a carousel container cannot be pending work");
            }
            if (written >= MAX_ENTRIES) {
                break;
            }
            appendItem(out, item);
            written++;
        }
        return out.toString();
    }

    /**
     * Decodes a ledger produced by {@link #encode(List)}.
     *
     * @throws IllegalArgumentException on malformed, oversized or unsupported
     *         input
     */
    public static List<MediaItem> decode(String encoded) {
        if (encoded == null) {
            throw new IllegalArgumentException("encoded ledger must not be null");
        }
        if (encoded.length() > MAX_ENCODED_LENGTH) {
            throw new IllegalArgumentException("encoded ledger is too large");
        }
        String[] lines = encoded.split(LINE_SEPARATOR, -1);
        if (lines.length == 0 || !(HEADER + SEPARATOR + VERSION).equals(lines[0])) {
            throw new IllegalArgumentException("unsupported pending-work header");
        }
        int last = lines.length;
        if (last > 1 && lines[last - 1].isEmpty()) {
            last--;
        }
        if (last - 1 > MAX_ENTRIES) {
            throw new IllegalArgumentException(
                    "pending work contains more than " + MAX_ENTRIES + " entries");
        }
        List<MediaItem> items = new ArrayList<MediaItem>(last - 1);
        Set<String> ids = new HashSet<String>();
        for (int i = 1; i < last; i++) {
            String line = lines[i];
            if (line.isEmpty()) {
                throw new IllegalArgumentException("pending work contains an empty line");
            }
            MediaItem item = decodeItem(line);
            if (!ids.add(item.id())) {
                throw new IllegalArgumentException(
                        "pending work contains a duplicate item id: " + item.id());
            }
            items.add(item);
        }
        return Collections.unmodifiableList(items);
    }

    private static void appendItem(StringBuilder out, MediaItem item) {
        out.append("item");
        field(out, item.id());
        field(out, item.kind().name());
        field(out, item.url());
        field(out, item.mimeType());
        out.append(SEPARATOR).append(item.sizeBytes());
        field(out, item.owner());
        out.append(SEPARATOR).append(item.width());
        out.append(SEPARATOR).append(item.height());
        out.append(SEPARATOR).append(item.expiresAtEpochMs());
        out.append(LINE_SEPARATOR);
    }

    private static MediaItem decodeItem(String line) {
        String[] parts = line.split(SEPARATOR, -1);
        if (parts.length != FIELD_COUNT) {
            throw new IllegalArgumentException(
                    "pending-work entry has " + parts.length
                            + " fields, expected " + FIELD_COUNT);
        }
        if (!"item".equals(parts[0])) {
            throw new IllegalArgumentException(
                    "pending-work entry has no item marker");
        }
        int index = 1;
        String id = unescape(parts[index++]);
        DownloadKind kind = parseKind(unescape(parts[index++]));
        String url = unescape(parts[index++]);
        String mimeType = unescape(parts[index++]);
        long sizeBytes = parseLong(parts[index++], "sizeBytes");
        String owner = unescape(parts[index++]);
        int width = parseInt(parts[index++], "width");
        int height = parseInt(parts[index++], "height");
        long expiresAt = parseLong(parts[index++], "expiresAtEpochMs");
        if (kind.isContainer()) {
            throw new IllegalArgumentException(
                    "pending work cannot contain a carousel container");
        }
        return new MediaItem.Builder(kind)
                .id(id)
                .url(url)
                .mimeType(mimeType)
                .sizeBytes(sizeBytes)
                .owner(owner)
                .size(width, height)
                .expiresAtEpochMs(expiresAt)
                .build();
    }

    private static DownloadKind parseKind(String value) {
        try {
            return DownloadKind.valueOf(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("unknown pending-work kind: " + value, e);
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

    /** Percent-encodes everything outside the unreserved set. */
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
                throw new IllegalArgumentException(
                        "raw non-ASCII character in pending work");
            } else {
                out.write((byte) c);
            }
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }
}
