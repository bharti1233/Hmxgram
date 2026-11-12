package com.feurstagram.extension.downloader;

/**
 * The kinds of media the downloader can handle.
 *
 * Pure Java on purpose: the resolution rules are unit-tested on the JVM without
 * an Android device (scripts/ci/test_downloader_logic.sh).
 */
public enum MediaKind {
    PHOTO(1),
    VIDEO(2),
    CAROUSEL(8),
    UNKNOWN(0);

    private final int igMediaType;

    MediaKind(int igMediaType) {
        this.igMediaType = igMediaType;
    }

    /** Instagram's own media_type values: 1 = image, 2 = video, 8 = carousel. */
    public int igMediaType() {
        return igMediaType;
    }

    public static MediaKind fromIgMediaType(int value) {
        for (MediaKind kind : values()) {
            if (kind != UNKNOWN && kind.igMediaType == value) return kind;
        }
        return UNKNOWN;
    }

    /** True when the item carries playable video rather than a still image. */
    public boolean hasVideo() {
        return this == VIDEO || this == CAROUSEL;
    }
}
