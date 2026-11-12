package com.feurstagram.extension.downloader;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The media the downloader can offer for one piece of Instagram media.
 *
 * Pure Java on purpose: the resolution rules are unit-tested on the JVM
 * without an Android device (scripts/ci/test_downloader_logic.sh).
 */
public final class MediaItem {

    /** What the item offers: the still image, the video, its audio, its cover. */
    public enum Variant {
        PHOTO("Download Photo"),
        VIDEO("Download Video"),
        AUDIO("Download Audio"),
        COVER("Download Cover");

        private final String label;

        Variant(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    private final String mediaId;
    private final String url;
    private final MediaKind kind;
    private final boolean audioOnly;
    private final boolean cover;
    private final String mimeType;
    private final String extension;
    private final int width;
    private final int height;

    private MediaItem(String mediaId, String url, MediaKind kind, boolean audioOnly,
                       boolean cover, String mimeType, String extension, int width, int height) {
        this.mediaId = mediaId;
        this.url = url;
        this.kind = kind;
        this.audioOnly = audioOnly;
        this.cover = cover;
        this.mimeType = mimeType;
        this.extension = extension;
        this.width = width;
        this.height = height;
    }

    public static MediaItem photo(String mediaId, String url, int width, int height) {
        return new MediaItem(mediaId, url, MediaKind.PHOTO, false, false,
                MediaNaming.mimeType(MediaKind.PHOTO, false),
                MediaNaming.extension(MediaKind.PHOTO, false), width, height);
    }

    public static MediaItem video(String mediaId, String url, boolean audioOnly, int width, int height) {
        return new MediaItem(mediaId, url, MediaKind.VIDEO, audioOnly, false,
                MediaNaming.mimeType(MediaKind.VIDEO, audioOnly),
                MediaNaming.extension(MediaKind.VIDEO, audioOnly), width, height);
    }

    public static MediaItem cover(String mediaId, String url, int width, int height) {
        return new MediaItem(mediaId, url, MediaKind.PHOTO, false, true,
                MediaNaming.mimeType(MediaKind.PHOTO, false),
                MediaNaming.extension(MediaKind.PHOTO, false), width, height);
    }

    public String mediaId() {
        return mediaId;
    }

    public String url() {
        return url;
    }

    public MediaKind kind() {
        return kind;
    }

    public boolean isAudioOnly() {
        return audioOnly;
    }

    /** Whether this item is the cover image of a video or a reel. */
    public boolean isCover() {
        return cover;
    }

    public String mimeType() {
        return mimeType;
    }

    public String extension() {
        return extension;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    /** The variant this item represents. */
    public Variant variant() {
        if (audioOnly) return Variant.AUDIO;
        if (cover) return Variant.COVER;
        return kind == MediaKind.VIDEO ? Variant.VIDEO : Variant.PHOTO;
    }

    /** The key used for duplicate detection. */
    public String duplicateKey() {
        return MediaNaming.duplicateKey(mediaId, url);
    }

    /** A file name for this item, based on the media id and the variant. */
    public String fileName(String owner) {
        String base = owner == null || owner.trim().isEmpty() ? "hmx-insta" : owner.trim();
        if (mediaId != null && !mediaId.trim().isEmpty()) {
            base = base + "-" + mediaId.trim();
        }
        return MediaNaming.fileName(base, extension);
    }

    @Override
    public String toString() {
        return "MediaItem{" + variant() + ", " + kind + ", id=" + mediaId + "}";
    }

    /**
     * The set of items one piece of Instagram media offers: a photo, a video,
     * its audio, its cover, or every child of a carousel.
     */
    public static final class Offer {
        private final MediaKind kind;
        private final List<MediaItem> items = new ArrayList<>();
        private final Map<Variant, String> unavailable = new LinkedHashMap<>();

        public Offer(MediaKind kind) {
            this.kind = kind;
        }

        public MediaKind kind() {
            return kind;
        }

        public List<MediaItem> items() {
            return items;
        }

        /** Why a variant could not be offered, keyed by variant. */
        public Map<Variant, String> unavailable() {
            return unavailable;
        }

        public void add(MediaItem item) {
            if (item != null) items.add(item);
        }

        /** Adds every item of another offer. */
        public void addAll(List<MediaItem> others) {
            if (others == null) return;
            for (MediaItem item : others) add(item);
        }

        public void unavailable(Variant variant, String reason) {
            unavailable.put(variant, reason);
        }

        public boolean isEmpty() {
            return items.isEmpty();
        }
    }
}
