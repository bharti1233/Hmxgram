package com.feurstagram.extension.downloader.core;

/**
 * Where a downloaded item will be written.
 *
 * <p>This is a pure data plan: the Android shell translates it into a
 * MediaStore insert with {@code IS_PENDING=1}, writes the bytes, then clears
 * the pending flag. The core never touches the filesystem.
 *
 * <p>The directory follows the documented architecture:
 * {@code Pictures/HMXInsta} for images, {@code Movies/HMXInsta} for video and
 * {@code Music/HMXInsta} for extracted audio. No broad storage permission is
 * involved.
 *
 * <p>Pure-Java core: no Android imports.
 */
public final class StoragePlan {

    public static final String IMAGE_DIRECTORY = "Pictures/HMXInsta";
    public static final String VIDEO_DIRECTORY = "Movies/HMXInsta";
    public static final String AUDIO_DIRECTORY = "Music/HMXInsta";

    private final DownloadKind kind;
    private final String relativeDirectory;
    private final String fileName;
    private final String mimeType;
    private final String duplicateKey;
    private final long expectedBytes;
    private final boolean pending;
    private final String temporaryFileName;

    private StoragePlan(DownloadKind kind, String relativeDirectory, String fileName,
            String mimeType, String duplicateKey, long expectedBytes) {
        this.kind = kind;
        this.relativeDirectory = relativeDirectory;
        this.fileName = fileName;
        this.mimeType = mimeType;
        this.duplicateKey = duplicateKey;
        this.expectedBytes = expectedBytes;
        this.pending = true;
        this.temporaryFileName = fileName + ".part";
    }

    /**
     * Builds the plan for one downloadable item.
     *
     * @throws IllegalArgumentException for a carousel container (expand it
     *         first) or an invalid timestamp
     */
    public static StoragePlan forItem(MediaItem item, long timestampMs) {
        if (item == null) {
            throw new IllegalArgumentException("item must not be null");
        }
        if (item.kind().isContainer()) {
            throw new IllegalArgumentException(
                    "expand a carousel before planning its storage");
        }
        if (timestampMs < 0L) {
            throw new IllegalArgumentException("timestamp must not be negative");
        }
        String directory;
        switch (item.kind()) {
            case PHOTO:
            case COVER:
                directory = IMAGE_DIRECTORY;
                break;
            case VIDEO:
                directory = VIDEO_DIRECTORY;
                break;
            case AUDIO:
                directory = AUDIO_DIRECTORY;
                break;
            default:
                throw new IllegalArgumentException(
                        "unsupported kind: " + item.kind());
        }
        return new StoragePlan(
                item.kind(),
                directory,
                MediaNaming.buildFileName(item, timestampMs),
                MediaNaming.mimeFor(item),
                MediaNaming.duplicateKey(item),
                item.sizeBytes());
    }

    public DownloadKind kind() {
        return kind;
    }

    /** Path relative to the shared storage root, for example {@code Pictures/HMXInsta}. */
    public String relativeDirectory() {
        return relativeDirectory;
    }

    public String fileName() {
        return fileName;
    }

    public String mimeType() {
        return mimeType;
    }

    public String duplicateKey() {
        return duplicateKey;
    }

    /** Declared size, or {@code -1} when unknown. */
    public long expectedBytes() {
        return expectedBytes;
    }

    /** Always {@code true}: the item must be inserted with {@code IS_PENDING}. */
    public boolean pending() {
        return pending;
    }

    /** The temporary file name used before the MediaStore publish. */
    public String temporaryFileName() {
        return temporaryFileName;
    }

    public boolean isImage() {
        return kind == DownloadKind.PHOTO || kind == DownloadKind.COVER;
    }

    public boolean isVideo() {
        return kind == DownloadKind.VIDEO;
    }

    public boolean isAudio() {
        return kind == DownloadKind.AUDIO;
    }

    /**
     * Joins a shared-storage root with this plan.
     *
     * @throws IllegalArgumentException when the root is blank or contains a
     *         {@code ..} segment
     */
    public String pathUnder(String storageRoot) {
        if (storageRoot == null || storageRoot.trim().isEmpty()) {
            throw new IllegalArgumentException("storageRoot must not be blank");
        }
        String root = storageRoot.trim();
        for (String segment : root.split("/")) {
            if ("..".equals(segment)) {
                throw new IllegalArgumentException("storageRoot must not contain ..");
            }
        }
        while (root.endsWith("/") && root.length() > 1) {
            root = root.substring(0, root.length() - 1);
        }
        if ("/".equals(root)) {
            return "/" + relativeDirectory + "/" + fileName;
        }
        return root + "/" + relativeDirectory + "/" + fileName;
    }

    @Override
    public String toString() {
        return "StoragePlan{" + relativeDirectory + "/" + fileName
                + ", mime=" + mimeType + ", pending=" + pending + '}';
    }
}
