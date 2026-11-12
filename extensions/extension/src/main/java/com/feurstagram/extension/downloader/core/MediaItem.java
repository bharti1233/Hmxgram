package com.feurstagram.extension.downloader.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * One downloadable item: the URL Instagram itself handed to the app, plus the
 * metadata needed to name and store it.
 *
 * <p>A {@link DownloadKind#CAROUSEL} item is a container: it has no URL of its
 * own and carries its children. Every other kind needs an absolute
 * {@code http://} or {@code https://} URL. The constructor validates this, so
 * an invalid item can never reach the queue.
 *
 * <p>Pure-Java core: no Android imports.
 */
public final class MediaItem {

    private final String id;
    private final DownloadKind kind;
    private final String url;
    private final String mimeType;
    private final long sizeBytes;
    private final String owner;
    private final int width;
    private final int height;
    private final List<MediaItem> children;
    private final long expiresAtEpochMs;

    private MediaItem(Builder builder) {
        this.id = builder.id == null ? "" : builder.id;
        this.kind = builder.kind;
        this.url = builder.url == null ? "" : builder.url;
        this.mimeType = builder.mimeType == null ? "" : builder.mimeType;
        this.sizeBytes = builder.sizeBytes;
        this.owner = builder.owner == null ? "" : builder.owner;
        this.width = builder.width;
        this.height = builder.height;
        this.children = Collections.unmodifiableList(
                new ArrayList<MediaItem>(builder.children));
        this.expiresAtEpochMs = builder.expiresAtEpochMs;
    }

    public String id() {
        return id;
    }

    public DownloadKind kind() {
        return kind;
    }

    public String url() {
        return url;
    }

    public String mimeType() {
        return mimeType;
    }

    /** Declared size in bytes, or {@code -1} when unknown. */
    public long sizeBytes() {
        return sizeBytes;
    }

    public String owner() {
        return owner;
    }

    /** Original width, or {@code 0} when unknown. */
    public int width() {
        return width;
    }

    /** Original height, or {@code 0} when unknown. */
    public int height() {
        return height;
    }

    /** Immutable child list; empty for every non-carousel kind. */
    public List<MediaItem> children() {
        return children;
    }

    /** {@code 0} when the item never expires; epoch millis otherwise. */
    public long expiresAtEpochMs() {
        return expiresAtEpochMs;
    }

    /** Whether this item has an expiry (stories do). */
    public boolean hasExpiry() {
        return expiresAtEpochMs > 0L;
    }

    /** Whether the item is expired at {@code nowMs}. */
    public boolean isExpired(long nowMs) {
        return expiresAtEpochMs > 0L && nowMs >= expiresAtEpochMs;
    }

    /**
     * Returns why this item cannot be downloaded right now, or {@code null}
     * when it can. The queue calls this at enqueue time so an expired story
     * never occupies a slot.
     */
    public DownloadError downloadability(long nowMs) {
        if (kind == null) {
            return DownloadError.INVALID_MEDIA;
        }
        if (kind.isContainer()) {
            if (children.isEmpty()) {
                return DownloadError.INVALID_MEDIA;
            }
            for (MediaItem child : children) {
                DownloadError childProblem = child.downloadability(nowMs);
                if (childProblem != null) {
                    return childProblem;
                }
            }
            return null;
        }
        if (!isHttpUrl(url)) {
            return DownloadError.INVALID_MEDIA;
        }
        if (isExpired(nowMs)) {
            return DownloadError.EXPIRED_MEDIA;
        }
        return null;
    }

    private static boolean isHttpUrl(String value) {
        if (value == null) {
            return false;
        }
        String lower = value.trim().toLowerCase(java.util.Locale.ROOT);
        return lower.startsWith("https://") || lower.startsWith("http://");
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof MediaItem)) {
            return false;
        }
        MediaItem that = (MediaItem) other;
        return Objects.equals(id, that.id)
                && kind == that.kind
                && Objects.equals(url, that.url);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, kind, url);
    }

    @Override
    public String toString() {
        return "MediaItem{id=" + id + ", kind=" + kind + ", owner=" + owner + '}';
    }

    /** Creates a validated {@link MediaItem}. */
    public static final class Builder {

        private final DownloadKind kind;
        private String id = "";
        private String url = "";
        private String mimeType = "";
        private long sizeBytes = -1L;
        private String owner = "";
        private int width;
        private int height;
        private final List<MediaItem> children = new ArrayList<MediaItem>();
        private long expiresAtEpochMs;

        public Builder(DownloadKind kind) {
            if (kind == null) {
                throw new IllegalArgumentException("kind must not be null");
            }
            this.kind = kind;
        }

        public Builder id(String value) {
            this.id = value;
            return this;
        }

        public Builder url(String value) {
            this.url = value;
            return this;
        }

        public Builder mimeType(String value) {
            this.mimeType = value;
            return this;
        }

        public Builder sizeBytes(long value) {
            this.sizeBytes = value;
            return this;
        }

        public Builder owner(String value) {
            this.owner = value;
            return this;
        }

        public Builder size(int widthPx, int heightPx) {
            this.width = widthPx;
            this.height = heightPx;
            return this;
        }

        public Builder expiresAtEpochMs(long value) {
            this.expiresAtEpochMs = value;
            return this;
        }

        public Builder addChild(MediaItem child) {
            if (child == null) {
                throw new IllegalArgumentException("child must not be null");
            }
            if (child.kind().isContainer()) {
                throw new IllegalArgumentException("carousel children cannot be carousels");
            }
            this.children.add(child);
            return this;
        }

        public Builder addChildren(List<MediaItem> children) {
            if (children == null) {
                throw new IllegalArgumentException("children must not be null");
            }
            for (MediaItem child : children) {
                addChild(child);
            }
            return this;
        }

        public MediaItem build() {
            if (sizeBytes < -1L) {
                throw new IllegalArgumentException("sizeBytes must be -1 or greater");
            }
            if (width < 0 || height < 0) {
                throw new IllegalArgumentException("dimensions must not be negative");
            }
            if (expiresAtEpochMs < 0L) {
                throw new IllegalArgumentException("expiry must not be negative");
            }
            if (kind.isContainer()) {
                if (children.isEmpty()) {
                    throw new IllegalArgumentException("a carousel needs at least one child");
                }
                if (!url.trim().isEmpty()) {
                    throw new IllegalArgumentException("a carousel has no URL of its own");
                }
            } else {
                if (!isHttpUrl(url)) {
                    throw new IllegalArgumentException(
                            "a downloadable item needs an http(s) URL");
                }
                if (!children.isEmpty()) {
                    throw new IllegalArgumentException(
                            "only a carousel may have children");
                }
            }
            return new MediaItem(this);
        }
    }
}
