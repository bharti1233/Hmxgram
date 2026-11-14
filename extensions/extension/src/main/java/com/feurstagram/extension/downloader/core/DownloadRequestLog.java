package com.feurstagram.extension.downloader.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * One traceable, redacted line per explicit download request.
 *
 * <p>The trace exists so a wrong download can be diagnosed from a log without
 * exposing anything sensitive. It records:
 *
 * <ul>
 *   <li>where the request came from (the feed row or the Reels page);</li>
 *   <li>the identity of the media selected in the UI;</li>
 *   <li>the identity of the media that was actually resolved;</li>
 *   <li>the exact targets handed to the queue and their outcome.</li>
 * </ul>
 *
 * <p>Sensitive values are never written. A media URL is reduced to its
 * scheme, host and a short SHA-256 fingerprint, so two observations of the
 * same file can be compared without printing the signed CDN query. Cookie,
 * token, authorisation and session values are scrubbed from every free-text
 * field; the downloader never reads them in the first place.
 *
 * <p>Pure-Java core: no Android imports.
 */
public final class DownloadRequestLog {

    private static final String[] SENSITIVE_KEYS = {
            "cookie", "authorization", "token", "session", "signature",
            "x-ig", "ig-set", "password"
    };

    private DownloadRequestLog() {
    }

    /**
     * Renders the trace of a request.
     *
     * @param source      the UI source, for example {@code feed-row}
     * @param surface     the Instagram surface
     * @param selected    the media bound to the tapped control
     * @param request     the evaluated request
     * @param added       queue items accepted
     * @param duplicates  queue items already queued or saved
     * @param failed      queue items refused
     */
    public static String entry(String source, InContextAction.Surface surface,
            ObservedMedia selected, ExplicitDownloadRequest request,
            int added, int duplicates, int failed) {
        StringBuilder line = new StringBuilder("hmx-request");
        line.append(" source=").append(scrub(source));
        line.append(" surface=").append(surface == null ? "unknown" : surface.name());
        line.append(" selected=").append(identity(selected));
        line.append(" status=").append(request == null ? "none" : request.status().name());
        line.append(" resolved=").append(identities(
                request == null ? null : request.targets()));
        line.append(" added=").append(added);
        line.append(" duplicates=").append(duplicates);
        line.append(" failed=").append(failed);
        return line.toString();
    }

    /** A trace before anything is enqueued. */
    public static String pending(String source, InContextAction.Surface surface,
            ObservedMedia selected, ExplicitDownloadRequest request) {
        return entry(source, surface, selected, request, 0, 0, 0);
    }

    /**
     * The identity of one media, safe to log.
     *
     * <p>The media id is numeric and not sensitive; it is what makes the
     * trace comparable with the queue. The URL is reduced to its host and a
     * fingerprint.
     */
    public static String identity(ObservedMedia media) {
        if (media == null) {
            return "none";
        }
        StringBuilder text = new StringBuilder();
        text.append(media.id() == null || media.id().trim().isEmpty()
                ? "no-id"
                : "id-" + fingerprint(media.id().trim()));
        text.append('/').append(media.kind() == null ? "unknown" : media.kind().name());
        if (media.kind() != null && media.kind().isContainer()) {
            text.append("/items=").append(media.children().size());
        }
        String url = media.url();
        if (url != null && !url.trim().isEmpty()) {
            text.append('/').append(hostOf(url));
            text.append('/').append(fingerprint(url));
        }
        return text.toString();
    }

    /** The identities of the resolved targets, comma separated. */
    public static String identities(List<ObservedMedia> targets) {
        if (targets == null || targets.isEmpty()) {
            return "none";
        }
        List<String> parts = new ArrayList<String>();
        for (ObservedMedia target : targets) {
            parts.add(identity(target));
        }
        return join(parts);
    }

    /**
     * The host of a URL, or {@code unknown} when it cannot be read.
     * The path, query and fragment are never returned.
     */
    public static String hostOf(String url) {
        if (url == null) {
            return "unknown";
        }
        String text = url.trim();
        int scheme = text.indexOf("://");
        if (scheme <= 0) {
            return "unknown";
        }
        int start = scheme + 3;
        int end = text.length();
        for (int index = start; index < text.length(); index++) {
            char character = text.charAt(index);
            if (character == '/' || character == '?' || character == '#') {
                end = index;
                break;
            }
        }
        if (end <= start) {
            return "unknown";
        }
        String host = text.substring(start, end);
        int at = host.lastIndexOf('@');
        if (at >= 0) {
            host = host.substring(at + 1);
        }
        int colon = host.indexOf(':');
        if (colon > 0) {
            host = host.substring(0, colon);
        }
        return host.isEmpty() ? "unknown" : host.toLowerCase(Locale.ROOT);
    }

    /**
     * A short, stable fingerprint of a URL.
     *
     * <p>It groups the observations of one file without exposing the signed
     * query. It is a hash, not a secret; the URL cannot be recovered from it.
     */
    public static String fingerprint(String value) {
        if (value == null) {
            return "none";
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int index = 0; index < 6 && index < bytes.length; index++) {
                hex.append(String.format(Locale.ROOT, "%02x", bytes[index] & 0xFF));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is required by every Java runtime; this is unreachable.
            return "hash-unavailable";
        }
    }

    /**
     * Removes sensitive values from a free-text field.
     *
     * <p>Only the fields this class writes itself are free text. Any key that
     * looks like a credential is replaced together with its value, so an
     * accidental {@code token=...} in a source label cannot leak.
     */
    public static String scrub(String value) {
        if (value == null || value.isEmpty()) {
            return "unknown";
        }
        // Only fixed source labels belong in logs. Unknown caller-provided text
        // may contain a URL, an account name, or a spaced credential header.
        if (!value.matches("[a-zA-Z0-9_-]{1,64}")) {
            return "label-" + fingerprint(value);
        }
        String text = value;
        String lower = text.toLowerCase(Locale.ROOT);
        for (String key : SENSITIVE_KEYS) {
            int from = 0;
            while (true) {
                int at = lower.indexOf(key, from);
                if (at < 0) {
                    break;
                }
                int end = at + key.length();
                while (end < text.length() && !isSeparator(text.charAt(end))) {
                    end++;
                }
                text = text.substring(0, at) + key + "=<redacted>"
                        + text.substring(end);
                lower = text.toLowerCase(Locale.ROOT);
                from = at + key.length() + "<redacted>".length();
            }
        }
        // A label is short; a very long value is a leaked URL or payload.
        if (text.length() > 80) {
            return text.substring(0, 80) + "…";
        }
        return text;
    }

    private static boolean isSeparator(char character) {
        return character == ' ' || character == '&' || character == ';'
                || character == ',' || character == '\n' || character == '\t';
    }

    private static String join(List<String> parts) {
        StringBuilder text = new StringBuilder();
        for (int index = 0; index < parts.size(); index++) {
            if (index > 0) {
                text.append(',');
            }
            text.append(parts.get(index));
        }
        return text.toString();
    }
}
