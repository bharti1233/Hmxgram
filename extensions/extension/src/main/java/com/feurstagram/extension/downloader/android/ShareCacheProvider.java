package com.feurstagram.extension.downloader.android;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.List;

/**
 * Read-only bridge between the share import and the download worker.
 *
 * <p>An {@code ACTION_SEND} grant belongs to the receiving activity, so it can
 * disappear as soon as that activity finishes. {@link ShareImporter} copies
 * the shared stream into the app's cache while the activity is still alive and
 * hands the worker a private {@code content://} URI served by this provider.
 * The core only knows content URIs, and the worker can read this one after the
 * activity is gone.
 *
 * <p>The provider is not exported and only serves files directly inside
 * {@code cache/hmx-share}. Path traversal, absolute paths and unknown
 * authorities are rejected.
 *
 * <p>No Instagram class, credential or internal is touched.
 */
public final class ShareCacheProvider extends ContentProvider {

    /** Suffix appended to the application package to form the authority. */
    public static final String AUTHORITY_SUFFIX = ".hmxshare";

    private static final String CACHE_DIRECTORY = "hmx-share";

    /** The authority declared by the share-target manifest patch. */
    static String authority(Context context) {
        return context.getPackageName() + AUTHORITY_SUFFIX;
    }

    /** Cache directory shared by the importer, the worker and this provider. */
    static File cacheDirectory(Context context) {
        return new File(context.getCacheDir(), CACHE_DIRECTORY);
    }

    /** Builds the private content URI for a cached share file. */
    static Uri uriFor(Context context, File file) {
        return new Uri.Builder()
                .scheme("content")
                .authority(authority(context))
                .appendPath(file.getName())
                .build();
    }

    /** Whether {@code url} points at this provider. */
    static boolean isShareUri(Context context, String url) {
        if (url == null) {
            return false;
        }
        try {
            Uri uri = Uri.parse(url);
            return "content".equals(uri.getScheme())
                    && authority(context).equals(uri.getAuthority())
                    && uri.getPathSegments().size() == 1;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Resolves a provider URI to a file, refusing anything outside the cache. */
    static File fileFor(Context context, Uri uri) throws FileNotFoundException {
        if (uri == null
                || !"content".equals(uri.getScheme())
                || !authority(context).equals(uri.getAuthority())) {
            throw new FileNotFoundException("unknown share authority");
        }
        List<String> segments = uri.getPathSegments();
        if (segments.size() != 1) {
            throw new FileNotFoundException("invalid share path");
        }
        String name = segments.get(0);
        if (name.isEmpty() || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0
                || ".".equals(name) || "..".equals(name)) {
            throw new FileNotFoundException("invalid share name");
        }
        File directory = cacheDirectory(context);
        File file = new File(directory, name);
        try {
            if (!directory.getCanonicalFile().equals(file.getCanonicalFile().getParentFile())) {
                throw new FileNotFoundException("share escapes the cache");
            }
        } catch (IOException e) {
            throw new FileNotFoundException("cannot resolve share path");
        }
        return file;
    }

    /** Deletes a cached share after it was published or abandoned. */
    static void deleteCopy(Context context, File file) {
        if (file == null) {
            return;
        }
        try {
            File directory = cacheDirectory(context);
            if (directory.getCanonicalFile().equals(file.getCanonicalFile().getParentFile())) {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
            }
        } catch (IOException ignored) {
            // Best effort: cache files are removed by the system anyway.
        }
    }

    /** Deletes a cached share by its private URI. */
    static void deleteCopy(Context context, String url) {
        if (!isShareUri(context, url)) {
            return;
        }
        try {
            deleteCopy(context, fileFor(context, Uri.parse(url)));
        } catch (FileNotFoundException ignored) {
            // Already gone.
        }
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode)
            throws FileNotFoundException {
        if (!"r".equals(mode)) {
            throw new FileNotFoundException("the share cache is read-only");
        }
        Context context = getContext();
        if (context == null) {
            throw new FileNotFoundException("no context");
        }
        File file = fileFor(context, uri);
        if (!file.isFile()) {
            throw new FileNotFoundException("share is missing");
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
            String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("read-only provider");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("read-only provider");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection,
            String[] selectionArgs) {
        throw new UnsupportedOperationException("read-only provider");
    }
}
