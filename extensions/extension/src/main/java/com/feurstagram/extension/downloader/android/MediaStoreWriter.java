package com.feurstagram.extension.downloader.android;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

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
 * only then is the pending flag cleared. Every failure path deletes the
 * pending row, so a broken download never leaves an empty gallery entry.
 *
 * <p>Android 9 and older have no scoped-storage columns, so the plan's
 * directory is created with a legacy {@code DATA} path. The app requests no
 * broad storage permission; an OEM that refuses the legacy write surfaces as
 * a normal storage failure.
 */
final class MediaStoreWriter {

    private static final int BUFFER_SIZE = 64 * 1024;

    private MediaStoreWriter() {
    }

    /**
     * Publishes {@code source} and returns the gallery path recorded in
     * history.
     *
     * @throws IOException when the row cannot be inserted, written or
     *         published
     */
    static String publish(Context context, StoragePlan plan, File source)
            throws IOException {
        if (plan == null || source == null) {
            throw new IllegalArgumentException("a publish needs a plan and a source");
        }
        if (!source.isFile() || source.length() <= 0L) {
            throw new IOException("the downloaded file is empty");
        }
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
            OutputStream raw = resolver.openOutputStream(uri);
            if (raw == null) {
                throw new IOException("MediaStore returned no output stream");
            }
            try (InputStream in = new BufferedInputStream(new FileInputStream(source));
                    OutputStream out = new BufferedOutputStream(raw)) {
                byte[] buffer = new byte[BUFFER_SIZE];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues done = new ContentValues();
                done.put(MediaStore.MediaColumns.IS_PENDING, 0);
                if (resolver.update(uri, done, null, null) <= 0) {
                    throw new IOException("MediaStore refused to publish the item");
                }
            }
            published = true;
            return plan.relativeDirectory() + "/" + plan.fileName();
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
