package com.feurstagram.extension.downloader;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Writes a downloaded file into the shared media collection.
 *
 * MediaStore is used directly, so no storage permission is needed: from
 * Android 10 onwards an app may add its own files to the shared collection
 * without holding a broad permission, and Android 15 behaves the same way.
 * The file is published with IS_PENDING so a half-written download never shows
 * up in the gallery.
 */
final class MediaStoreWriter {

    private MediaStoreWriter() {
    }

    /**
     * The collection a file is added to. The per-volume collections exist from
     * Android 10 onwards; on the older platform the single external collection
     * is used, so the downloader works there too.
     */
    private static Uri collectionFor(Context context, boolean video) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            return video
                    ? MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                    : MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        }
        return video ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                : MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
    }

    /**
     * Publishes an extracted audio track in the gallery.
     *
     * @param context the context
     * @param source  the extracted audio file
     * @param item    the item the audio came from
     * @param name    the file name to use
     * @return the gallery location, or null when the file could not be stored
     */
    static String writeAudio(Context context, File source, MediaItem item, String name) {
        if (context == null || source == null || !source.exists()) return null;

        Uri collection = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q
                ? MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                : MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        values.put(MediaStore.MediaColumns.MIME_TYPE, "audio/mp4");
        values.put(MediaStore.MediaColumns.RELATIVE_PATH,
                Environment.DIRECTORY_MUSIC + "/" + MediaNaming.ALBUM);
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);

        Uri inserted = null;
        try {
            inserted = context.getContentResolver().insert(collection, values);
            if (inserted == null) return null;
            try (InputStream input = new FileInputStream(source);
                 OutputStream output = context.getContentResolver().openOutputStream(inserted)) {
                if (output == null) return null;
                byte[] buffer = new byte[131072];
                int read;
                while ((read = input.read(buffer)) > 0) {
                    output.write(buffer, 0, read);
                }
            }
        } catch (Throwable t) {
            if (inserted != null) {
                try {
                    context.getContentResolver().delete(inserted, null, null);
                } catch (Throwable ignored) {
                    // Nothing else to do: the row is removed.
                }
            }
            return null;
        }

        ContentValues done = new ContentValues();
        done.put(MediaStore.MediaColumns.IS_PENDING, 0);
        try {
            context.getContentResolver().update(inserted, done, null, null);
        } catch (Throwable ignored) {
            // The file is written either way.
        }
        return inserted.toString();
    }

    /**
     * Publishes the file in the gallery.
     *
     * @param context the context
     * @param source  the downloaded file
     * @param item    what was downloaded
     * @return the gallery location, or null when the file could not be stored
     */
    static String write(Context context, File source, MediaItem item) {
        if (context == null || source == null || !source.exists()) return null;

        Uri collection = collectionFor(context, item.kind() == MediaKind.VIDEO);
        String relative = item.kind() == MediaKind.VIDEO
                ? Environment.DIRECTORY_MOVIES + "/" + MediaNaming.ALBUM
                : Environment.DIRECTORY_PICTURES + "/" + MediaNaming.ALBUM;

        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, item.fileName(null));
        values.put(MediaStore.MediaColumns.MIME_TYPE, item.mimeType());
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, relative);
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);

        Uri inserted = null;
        try {
            inserted = context.getContentResolver().insert(collection, values);
            if (inserted == null) return null;
            try (InputStream input = new FileInputStream(source);
                 OutputStream output = context.getContentResolver().openOutputStream(inserted)) {
                if (output == null) return null;
                byte[] buffer = new byte[131072];
                int read;
                while ((read = input.read(buffer)) > 0) {
                    output.write(buffer, 0, read);
                }
            }
        } catch (Throwable t) {
            if (inserted != null) {
                try {
                    context.getContentResolver().delete(inserted, null, null);
                } catch (Throwable ignored) {
                    // Nothing else to do: the row is removed.
                }
            }
            return null;
        }

        // Publish it: the gallery only shows the file once it is complete.
        ContentValues done = new ContentValues();
        done.put(MediaStore.MediaColumns.IS_PENDING, 0);
        try {
            context.getContentResolver().update(inserted, done, null, null);
        } catch (Throwable ignored) {
            // The file is written either way.
        }
        return inserted.toString();
    }
}
