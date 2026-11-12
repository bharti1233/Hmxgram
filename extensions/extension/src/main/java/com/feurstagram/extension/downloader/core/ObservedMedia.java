package com.feurstagram.extension.downloader.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One media item observed while the user browsed Instagram.
 *
 * <p>This is a plain copy of what the user was looking at. It never holds an
 * Instagram object, a session, a cookie or a token: only the values a
 * download needs, so it can be stored, listed and handed to the download
 * engine without touching Instagram's model.
 *
 * <p>Pure-Java core: no Android imports.
 */
public final class ObservedMedia {

    private final String id;
    private final DownloadKind kind;
    private final String url;
    private final String mimeType;
    private final String owner;
    private final int width;
    private final int height;
    private final long sizeBytes;
    private final long expiresAtEpochMs;
    private final boolean hasAudio;
    private final List<ObservedMedia> children;

    private ObservedMedia(String id, DownloadKind kind, String url, String mimeType,
            String owner, int width, int height, long sizeBytes,
            long expiresAtEpochMs, boolean hasAudio, List<ObservedMedia> children) {
        this.id = id;
        this.kind = kind;
        this.url = url;
        this.mimeType = mimeType;
        this.owner = owner;
        this.width = width;
        this.height = height;
        this.sizeBytes = sizeBytes;
        this.expiresAtEpochMs = expiresAtEpochMs;
        this.hasAudio = hasAudio;
        this.children = children == null || children.isEmpty()
                ? Collections.<ObservedMedia>emptyList()
                : Collections.unmodifiableList(new ArrayList<ObservedMedia>(children));
    }

    /** A single photo, video or audio item. */
    public static ObservedMedia single(String id, DownloadKind kind, String url,
            String mimeType, String owner, int width, int height, long sizeBytes,
            long expiresAtEpochMs, boolean hasAudio) {
        if (kind == null) {
            throw new IllegalArgumentException("kind must not be null");
        }
        if (kind.isContainer()) {
            throw new IllegalArgumentException("a container needs its children");
        }
        return new ObservedMedia(id, kind, url, mimeType, owner, width, height,
                sizeBytes, expiresAtEpochMs, hasAudio, null);
    }

    /** A carousel: the container plus its children. */
    public static ObservedMedia carousel(String id, List<ObservedMedia> children,
            String owner, long expiresAtEpochMs) {
        if (children == null || children.isEmpty()) {
            throw new IllegalArgumentException("a carousel needs at least one child");
        }
        return new ObservedMedia(id, DownloadKind.CAROUSEL, null, null, owner, 0, 0,
                0L, expiresAtEpochMs, false, children);
    }

    public String id() {
        return id;
    }

    public DownloadKind kind() {
        return kind;
    }

    /** The direct media URL, or {@code null} for a container. */
    public String url() {
        return url;
    }

    public String mimeType() {
        return mimeType;
    }

    public String owner() {
        return owner;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public long sizeBytes() {
        return sizeBytes;
    }

    /** When the CDN URL stops working, or {@code 0} when unknown. */
    public long expiresAtEpochMs() {
        return expiresAtEpochMs;
    }

    /**
     * Whether the original media carries an audio track.
     *
     * <p>Only a media that says so may be offered as an audio download: the
     * downloader refuses to pretend a muted video has audio.
     */
    public boolean hasAudio() {
        return hasAudio;
    }

    /** The carousel children, empty for a single item. */
    public List<ObservedMedia> children() {
        return children;
    }

    /** Whether this item can be handed to the download engine as it is. */
    public boolean isDownloadable() {
        if (kind.isContainer()) {
            return false;
        }
        return url != null && url.startsWith("http");
    }

    /**
     * Converts this observation into an engine item.
     *
     * @throws IllegalArgumentException when the observation has no usable URL
     */
    public MediaItem toMediaItem() {
        if (!isDownloadable()) {
            throw new IllegalArgumentException(
                    "this observation has no downloadable URL: " + kind);
        }
        MediaItem.Builder builder = new MediaItem.Builder(kind)
                .id(id == null || id.trim().isEmpty() ? null : id)
                .url(url)
                .mimeType(mimeType)
                .owner(owner == null ? "instagram" : owner);
        if (width > 0 && height > 0) {
            builder.size(width, height);
        }
        if (sizeBytes > 0L) {
            builder.sizeBytes(sizeBytes);
        }
        if (expiresAtEpochMs > 0L) {
            builder.expiresAtEpochMs(expiresAtEpochMs);
        }
        return builder.build();
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ObservedMedia)) {
            return false;
        }
        ObservedMedia that = (ObservedMedia) other;
        return kind == that.kind
                && equal(id, that.id)
                && equal(url, that.url)
                && children.equals(that.children);
    }

    @Override
    public int hashCode() {
        int hash = id == null ? 0 : id.hashCode();
        hash = 31 * hash + kind.hashCode();
        hash = 31 * hash + (url == null ? 0 : url.hashCode());
        hash = 31 * hash + children.hashCode();
        return hash;
    }

    @Override
    public String toString() {
        return "ObservedMedia{" + id + ", " + kind + ", " + url + "}";
    }

    private static boolean equal(Object left, Object right) {
        return left == null ? right == null : left.equals(right);
    }
}
