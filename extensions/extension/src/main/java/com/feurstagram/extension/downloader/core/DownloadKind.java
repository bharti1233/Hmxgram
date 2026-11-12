package com.feurstagram.extension.downloader.core;

/**
 * What a downloadable item is.
 *
 * <p>The first three values mirror Instagram's own media-type field, so the
 * Android shell can map a model to a kind without duplicating the rule:
 * {@code 1 = image}, {@code 2 = video}, {@code 8 = carousel}. {@link #AUDIO}
 * and {@link #COVER} are derived outputs of a video, not Instagram media
 * types; they carry {@code 0} and are never returned by
 * {@link #fromInstagramMediaType(int)}.
 *
 * <p>This class is part of the pure-Java downloader core: it must not import
 * any Android or AndroidX class.
 */
public enum DownloadKind {

    /** A single still image. */
    PHOTO(1),

    /** A single video. */
    VIDEO(2),

    /** A container whose children are photos and/or videos. */
    CAROUSEL(8),

    /** The audio track extracted from a video. */
    AUDIO(0),

    /** The still cover/thumbnail of a video or reel. */
    COVER(0);

    private final int instagramMediaType;

    DownloadKind(int instagramMediaType) {
        this.instagramMediaType = instagramMediaType;
    }

    /**
     * Maps Instagram's numeric media type to a kind.
     *
     * @throws IllegalArgumentException for any value other than 1, 2 or 8
     */
    public static DownloadKind fromInstagramMediaType(int mediaType) {
        switch (mediaType) {
            case 1:
                return PHOTO;
            case 2:
                return VIDEO;
            case 8:
                return CAROUSEL;
            default:
                throw new IllegalArgumentException(
                        "Unsupported Instagram media type: " + mediaType);
        }
    }

    /** Whether this kind is a container that must be expanded before download. */
    public boolean isContainer() {
        return this == CAROUSEL;
    }

    /** Whether this kind maps to one of Instagram's own numeric media types. */
    public boolean hasInstagramMediaType() {
        return instagramMediaType != 0;
    }

    /** Instagram's numeric media type, or {@code 0} for derived kinds. */
    public int instagramMediaType() {
        return instagramMediaType;
    }
}
