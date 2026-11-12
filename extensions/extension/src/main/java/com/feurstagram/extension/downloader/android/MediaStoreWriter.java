package com.feurstagram.extension.downloader.android;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import com.feurstagram.extension.downloader.core.CancellationSignal;
import com.feurstagram.extension.downloader.core.GuardedCopy;
import com.feurstagram.extension.downloader.core.StoragePlan;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Writes a finished download into the shared gallery through MediaStore.
 *
 * <p>On Android 10 and later the row is inserted with
 * {@code RELATIVE_PATH} and {@code IS_PENDING=1}, the bytes are copied, and
 * only then is the pending flag cleared. Every failure path — including
 * cancellation at any point — deletes the pending row, so a broken download
 * never leaves an empty gallery entry. The cancel signal is checked before the
 * insert, before the copy, on every buffer and before the row is published;
 * a cancel that still lands after publication deletes the published row
 * through {@link #discard(Context, Uri)}.
 *
 * <p>Android 9 and older have no scoped-storage columns, so the plan's
 * directory is created with a legacy {@code DATA} path. The app requests no
 * broad storage permission; an OEM that refuses the legacy write surfaces as
 * a normal storage failure.
 */
final class MediaStoreWriter {

    private MediaStoreWriter() {
    }

    /** A published gallery item: its row URI and the path recorded in history. */
    static final class Published {
        final Uri uri;
        final String path;

        Published(Uri uri, String path) {
            this.uri = uri;
            this.path = path;
        }

        Uri uri() {
            return uri;
        }

        String path() {
            return path;
        }
    }

    /**
     * Publishes {@code source} and returns the row and the gallery path.
     *
     * @throws com.feurstagram.extension.downloader.core.DownloadCancelledException
     *         when the signal was set before or during the write
     * @throws IOException when the row cannot be inserted, written or
     *         published
     */
    static Published publish(Context context, StoragePlan plan, File source,
            CancellationSignal cancelled) throws IOException {
        if (plan == null || source == null) {
            throw new IllegalArgumentException("a publish needs a plan and a source");
        }
        if (cancelled == null) {
            throw new IllegalArgumentException("a publish needs a cancellation signal");
        }
        if (!source.isFile() || source.length() <= 0L) {
            throw new IOException("the downloaded file is empty");
        }
        cancelled.throwIfCancelled();

        ContentResolver resolver = context.getContentResolver();
        Uri collection = collectionFor(plan);
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, plan.fileName());
        values.put(MediaStore.MediaColumns.MIME_TYPE, plan.mimeType());

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, plan.relativeDirectory());
            values.put(MediaStore.MediaColumns.IS_PENDING, 1);
        } else {
            File directory = legacyDirectory(plan);
            if (!directory.exists() && !directory.mkdirs()) {
                throw new IOException("cannot create the gallery directory");
            }
            values.put(MediaStore.MediaColumns.DATA,
                    new File(directory, plan.fileName()).getAbsolutePath());
        }

        Uri uri = resolver.insert(collection, values);
        if (uri == null) {
            throw new IOException("MediaStore refused the insert");
        }
        boolean published = false;
        try {
            cancelled.throwIfCancelled();
            OutputStream raw = resolver.openOutputStream(uri);
            if (raw == null) {
                throw new IOException("MediaStore returned no output stream");
            }
            try (InputStream in = new BufferedInputStream(new FileInputStream(source));
                    OutputStream out = new BufferedOutputStream(raw)) {
                // Every cancelled or failed copy deletes the pending row.
                GuardedCopy.copy(in, out, cancelled, new GuardedCopy.Discard() {
                    @Override
                    public void discard() {
                        resolver.delete(uri, null, null);
                    }
                });
            }
            cancelled.throwIfCancelled();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues done = new ContentValues();
                done.put(MediaStore.MediaColumns.IS_PENDING, 0);
                if (resolver.update(uri, done, null, null) <= 0) {
                    throw new IOException("MediaStore refused to publish the item");
                }
            }
            published = true;
            return new Published(uri, plan.relativeDirectory() + "/" + plan.fileName());
        } catch (IOException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new IOException("MediaStore write failed", e);
        } finally {
            if (!published) {
                try {
                    resolver.delete(uri, null, null);
                } catch (RuntimeException ignored) {
                    // The row is already gone, or the provider refused the
                    // cleanup; either way there is nothing better to do.
                }
            }
        }
    }

    /**
     * Deletes a published row after a late cancellation. Best effort: if the
     * provider refuses, the item simply stays, and the history entry says
     * cancelled.
     */
    static void discard(Context context, Uri uri) {
        if (context == null || uri == null) {
            return;
        }
        try {
            context.getContentResolver().delete(uri, null, null);
        } catch (RuntimeException ignored) {
            // The row may already be gone.
        }
    }

    /**
     * Deletes a published item by its history path. Used when a cancellation
     * landed between the final publish and the queue transition, so a
     * cancelled task can never keep a finished file.
     */
    static void discardByPath(Context context, String path) {
        if (context == null || path == null || path.trim().isEmpty()) {
            return;
        }
        String trimmed = path.trim();
        if (trimmed.startsWith("/")) {
            // Pre-Q legacy writes went straight to a file.
            new File(trimmed).delete();
            return;
        }
        int slash = trimmed.lastIndexOf('/');
        if (slash <= 0) {
            return;
        }
        String directory = trimmed.substring(0, slash);
        String name = trimmed.substring(slash + 1);
        Uri collection;
        if (directory.startsWith("Music/")) {
            collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
        } else if (directory.startsWith("Movies/")) {
            collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
        } else {
            collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        }
        try {
            context.getContentResolver().delete(collection,
                    MediaStore.MediaColumns.RELATIVE_PATH + "=? AND "
                            + MediaStore.MediaColumns.DISPLAY_NAME + "=?",
                    new String[]{directory + "/", name});
        } catch (RuntimeException ignored) {
            // Cleanup is best effort; the history entry stays authoritative.
        }
    }

    private static Uri collectionFor(StoragePlan plan) {
        if (plan.isImage()) {
            return MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        }
        if (plan.isVideo()) {
            return MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
        }
        if (plan.isAudio()) {
            return MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
        }
        throw new IllegalArgumentException("unsupported storage kind");
    }

    @SuppressWarnings("deprecation")
    private static File legacyDirectory(StoragePlan plan) {
        String publicDirectory;
        if (plan.isAudio()) {
            publicDirectory = Environment.DIRECTORY_MUSIC;
        } else if (plan.isImage()) {
            publicDirectory = Environment.DIRECTORY_PICTURES;
        } else {
            publicDirectory = Environment.DIRECTORY_MOVIES;
        }
        return new File(Environment.getExternalStoragePublicDirectory(publicDirectory),
                "HMXInsta");
    }
}
