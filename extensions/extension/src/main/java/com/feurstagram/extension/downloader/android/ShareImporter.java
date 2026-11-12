package com.feurstagram.extension.downloader.android;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;

import com.feurstagram.extension.downloader.core.DownloadKind;
import com.feurstagram.extension.downloader.core.MediaItem;
import com.feurstagram.extension.downloader.core.MediaNaming;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Copies a shared stream into private cache storage and turns it into a
 * downloader {@link MediaItem}.
 *
 * <p>The copy happens before the activity finishes because the sender's URI
 * grant is tied to the activity. The stable id is
 * {@code share-<sha256-of-bytes>}, so sharing the same photo twice is detected
 * as a duplicate without relying on a temporary grant URI. The worker reads
 * the cached copy through {@link ShareCacheProvider}.
 */
final class ShareImporter {

    private static final int BUFFER_SIZE = 64 * 1024;

    static final class Imported {
        final MediaItem item;
        final File file;

        Imported(MediaItem item, File file) {
            this.item = item;
            this.file = file;
        }
    }

    private ShareImporter() {
    }

    static Imported importOne(Context context, ShareIntentParser.Payload payload)
            throws IOException {
        if (context == null || payload == null) {
            throw new IllegalArgumentException("a share needs a context and a payload");
        }
        File directory = ShareCacheProvider.cacheDirectory(context);
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IOException("cannot create the share cache");
        }

        String extension = MediaNaming.extensionForMime(payload.mimeType);
        if ("bin".equals(extension)) {
            extension = payload.mimeType.startsWith("video/") ? "mp4" : "jpg";
        }
        File target = File.createTempFile("share-", "." + extension, directory);
        MessageDigest digest = sha256();
        long bytes = 0L;
        boolean complete = false;
        try {
            ContentResolver resolver = context.getContentResolver();
            InputStream raw = resolver.openInputStream(payload.uri);
            if (raw == null) {
                throw new IOException("the shared stream is unavailable");
            }
            try (InputStream in = new BufferedInputStream(raw);
                    BufferedOutputStream out = new BufferedOutputStream(
                            new FileOutputStream(target))) {
                byte[] buffer = new byte[BUFFER_SIZE];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    bytes += read;
                    if (bytes > ShareIntentParser.MAX_ITEM_BYTES) {
                        throw new IOException("the shared item is too large");
                    }
                    digest.update(buffer, 0, read);
                    out.write(buffer, 0, read);
                }
            }
            if (bytes == 0L) {
                throw new IOException("the shared item is empty");
            }
            complete = true;
        } finally {
            if (!complete) {
                //noinspection ResultOfMethodCallIgnored
                target.delete();
            }
        }

        String id = "share-" + hex(digest.digest());
        DownloadKind kind = payload.mimeType.startsWith("video/")
                ? DownloadKind.VIDEO
                : DownloadKind.PHOTO;
        Uri uri = ShareCacheProvider.uriFor(context, target);
        MediaItem item = new MediaItem.Builder(kind)
                .id(id)
                .url(uri.toString())
                .mimeType(payload.mimeType)
                .sizeBytes(bytes)
                .owner("share")
                .build();
        return new Imported(item, target);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandatory on every Android runtime.
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            out.append(Character.forDigit((value >> 4) & 0x0f, 16));
            out.append(Character.forDigit(value & 0x0f, 16));
        }
        return out.toString();
    }
}
