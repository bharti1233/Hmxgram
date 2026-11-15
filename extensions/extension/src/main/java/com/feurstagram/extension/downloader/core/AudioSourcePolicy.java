package com.feurstagram.extension.downloader.core;

import java.util.Locale;

/**
 * Decides how an {@link DownloadKind#AUDIO} item is produced.
 *
 * <p>Two very different inputs end in the same gallery folder:
 *
 * <ul>
 *   <li>an audio share is already audio and is published as-is;</li>
 *   <li>a video share needs its first audio track muxed into an {@code .m4a}
 *       container, which Android's {@code MediaExtractor}/{@code MediaMuxer}
 *       only guarantee for MP4-family containers.</li>
 * </ul>
 *
 * <p>Anything else is rejected as {@code unsupported} instead of being passed
 * to the platform and failing with an opaque exception. The policy is pure so
 * every input class has a unit test; the actual muxer is device-verified (or
 * recorded as NOT TESTED).
 *
 * <p>Pure-Java core: no Android imports.
 */
public final class AudioSourcePolicy {

    /** What the audio pipeline should do with a source. */
    public enum Route {
        /** The source already is audio; publish it without extraction. */
        DIRECT_AUDIO,
        /** The source is a supported video container; extract track 1 to M4A. */
        EXTRACT_FROM_VIDEO,
        /** Unknown or unsupported; fail with {@link DownloadError#UNSUPPORTED}. */
        UNSUPPORTED
    }

    private AudioSourcePolicy() {
    }

    /**
     * Classifies a MIME type. Parameters are ignored and matching is
     * case-insensitive.
     */
    public static Route route(String mimeType) {
        String normalized = normalize(mimeType);
        if (normalized == null) {
            return Route.UNSUPPORTED;
        }
        if (normalized.startsWith("audio/")) {
            return Route.DIRECT_AUDIO;
        }
        if (isSupportedVideoContainer(normalized)) {
            return Route.EXTRACT_FROM_VIDEO;
        }
        return Route.UNSUPPORTED;
    }

    /**
     * MP4-family containers that Android's extractor and MPEG-4 muxer handle
     * reliably. WebM/MKV are deliberately excluded: the extractor may read
     * them, but the muxer cannot always write their audio codecs into MP4, and
     * a half-written {@code .m4a} is worse than an honest refusal.
     */
    public static boolean isSupportedVideoContainer(String mimeType) {
        String normalized = normalize(mimeType);
        if (normalized == null) {
            return false;
        }
        switch (normalized) {
            case "video/mp4":
            case "video/x-m4v":
            case "video/3gpp":
            case "video/3gpp2":
            case "video/quicktime":
                return true;
            default:
                return false;
        }
    }

    /** Whether the source is audio already and needs no extraction. */
    public static boolean isDirectAudio(String mimeType) {
        return route(mimeType) == Route.DIRECT_AUDIO;
    }

    private static String normalize(String mimeType) {
        if (mimeType == null) {
            return null;
        }
        String normalized = mimeType.trim().toLowerCase(Locale.ROOT);
        int parameter = normalized.indexOf(';');
        if (parameter >= 0) {
            normalized = normalized.substring(0, parameter).trim();
        }
        return normalized.isEmpty() ? null : normalized;
    }
}
