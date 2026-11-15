package com.feurstagram.extension.downloader.android;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;

import com.feurstagram.extension.downloader.core.AudioSourcePolicy;
import com.feurstagram.extension.downloader.core.CancellationSignal;
import com.feurstagram.extension.downloader.core.DownloadCancelledException;
import com.feurstagram.extension.downloader.core.DownloadError;
import com.feurstagram.extension.downloader.core.DownloadResult;
import com.feurstagram.extension.downloader.core.DownloadTask;
import com.feurstagram.extension.downloader.core.MediaItem;
import com.feurstagram.extension.downloader.core.StoragePlan;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;

/**
 * Executes one running task: HTTP(S) or a private share {@code content://}
 * stream into a cache file, then publishes that file to MediaStore.
 *
 * <p>Every stage honours the task's {@link CancellationSignal}: before the
 * download, between the download and the audio step, during the copy loop and
 * before and during the MediaStore publish. A cancellation never leaves a
 * published file behind.
 *
 * <p>No cookie, authorization header, Instagram host or session is ever read.
 * The only request made is to the URL carried by the {@link MediaItem}, and
 * the only content stream opened is the user-selected share.
 *
 * <p>The worker is package-private and owns no state; the queue owns task
 * transitions.
 */
final class DownloadWorker {

    /** Progress callback, delivered on the worker thread. */
    interface ProgressListener {
        void onProgress(long bytesRead, long totalBytes);
    }

    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int READ_TIMEOUT_MS = 30000;
    private static final int BUFFER_SIZE = 64 * 1024;
    private static final long MAX_DOWNLOAD_BYTES = 1024L * 1024L * 1024L;
    private static final long PROGRESS_STEP = 256L * 1024L;

    private DownloadWorker() {
    }

    /** Removes a published gallery row for a late-cancelled task. */
    static void discardPublished(Context context, String path) {
        MediaStoreWriter.discardByPath(context, path);
    }

    /**
     * Runs the download and returns a result instead of throwing, so the
     * caller can always finish the queue transition.
     */
    static DownloadResult execute(Context context, DownloadTask task,
            CancellationSignal cancelled, ProgressListener listener) {
        MediaItem item = task.item();
        StoragePlan plan;
        try {
            plan = StoragePlan.forItem(item, task.createdAtMs());
        } catch (IllegalArgumentException e) {
            return DownloadResult.failure(DownloadError.INVALID_MEDIA);
        }

        File downloaded = null;
        File audio = null;
        try {
            cancelled.throwIfCancelled();
            downloaded = File.createTempFile("hmx-download-", ".part",
                    context.getCacheDir());
            downloadTo(context, item, cancelled, downloaded, listener);
            cancelled.throwIfCancelled();

            File publishSource = downloaded;
            if (plan.isAudio()) {
                AudioSourcePolicy.Route route = AudioSourcePolicy.route(item.mimeType());
                if (route == AudioSourcePolicy.Route.DIRECT_AUDIO) {
                    // Already audio: publish the bytes as they arrived.
                } else if (route == AudioSourcePolicy.Route.EXTRACT_FROM_VIDEO) {
                    cancelled.throwIfCancelled();
                    audio = new File(downloaded.getParentFile(),
                            downloaded.getName() + ".m4a");
                    if (!AudioExtractor.extract(downloaded, audio)) {
                        return DownloadResult.failure(DownloadError.UNSUPPORTED);
                    }
                    cancelled.throwIfCancelled();
                    publishSource = audio;
                } else {
                    // Honest refusal for containers we cannot mux to M4A.
                    return DownloadResult.failure(DownloadError.UNSUPPORTED);
                }
            }

            MediaStoreWriter.Published published =
                    MediaStoreWriter.publish(context, plan, publishSource, cancelled);
            if (cancelled.isCancelled()) {
                // A cancel that landed between the publish and this check must
                // not leave a finished file behind.
                MediaStoreWriter.discard(context, published.uri());
                return DownloadResult.cancelled();
            }
            return DownloadResult.success(published.path(), publishSource.length());
        } catch (DownloadCancelledException e) {
            return DownloadResult.cancelled();
        } catch (TimeoutFailure e) {
            return DownloadResult.failure(DownloadError.TIMEOUT);
        } catch (ExpiredFailure e) {
            return DownloadResult.failure(DownloadError.EXPIRED_LINK);
        } catch (ServerFailure e) {
            return DownloadResult.failure(DownloadError.SERVER);
        } catch (SourceFailure e) {
            return DownloadResult.failure(DownloadError.INVALID_MEDIA);
        } catch (NetworkFailure e) {
            return DownloadResult.failure(DownloadError.NETWORK);
        } catch (StorageFailure e) {
            return DownloadResult.failure(DownloadError.STORAGE);
        } catch (UnsupportedFailure e) {
            return DownloadResult.failure(DownloadError.UNSUPPORTED);
        } catch (IOException e) {
            return DownloadResult.failure(DownloadError.STORAGE);
        } catch (SecurityException e) {
            return DownloadResult.failure(DownloadError.INVALID_MEDIA);
        } catch (RuntimeException e) {
            return DownloadResult.failure(DownloadError.UNKNOWN);
        } finally {
            delete(downloaded);
            delete(audio);
        }
    }

    private static void downloadTo(Context context, MediaItem item,
            CancellationSignal cancelled, File target, ProgressListener listener)
            throws IOException, TimeoutFailure, ExpiredFailure, ServerFailure,
            NetworkFailure, SourceFailure, StorageFailure, UnsupportedFailure {
        String url = item.url();
        if (url.regionMatches(true, 0, "content://", 0, "content://".length())) {
            downloadContent(context, Uri.parse(url), cancelled, target, listener);
        } else if (url.regionMatches(true, 0, "https://", 0, 8)
                || url.regionMatches(true, 0, "http://", 0, 7)) {
            downloadHttp(url, cancelled, target, listener);
        } else {
            // MediaItem already rejects this, but the worker fails closed.
            throw new UnsupportedFailure();
        }
    }

    private static void downloadContent(Context context, Uri uri,
            CancellationSignal cancelled, File target, ProgressListener listener)
            throws IOException, TimeoutFailure, NetworkFailure, SourceFailure,
            StorageFailure {
        ContentResolver resolver = context.getContentResolver();
        InputStream in;
        try {
            in = resolver.openInputStream(uri);
        } catch (SecurityException e) {
            throw new SourceFailure();
        } catch (IOException e) {
            throw new SourceFailure();
        }
        if (in == null) {
            throw new SourceFailure();
        }
        try {
            copy(in, target, cancelled, listener, -1L, false);
        } finally {
            closeQuietly(in);
        }
    }

    private static void downloadHttp(String url, CancellationSignal cancelled,
            File target, ProgressListener listener) throws IOException,
            TimeoutFailure, ExpiredFailure, ServerFailure, NetworkFailure,
            SourceFailure, StorageFailure, UnsupportedFailure {
        HttpURLConnection connection = null;
        InputStream in = null;
        try {
            cancelled.throwIfCancelled();
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setInstanceFollowRedirects(true);
            connection.setUseCaches(false);
            connection.setRequestProperty("Accept", "*/*");
            connection.setRequestProperty("User-Agent", "HMXInsta/1.0");
            int code = connection.getResponseCode();
            cancelled.throwIfCancelled();
            if (code == HttpURLConnection.HTTP_UNAUTHORIZED
                    || code == HttpURLConnection.HTTP_FORBIDDEN
                    || code == HttpURLConnection.HTTP_NOT_FOUND
                    || code == HttpURLConnection.HTTP_GONE) {
                throw new ExpiredFailure();
            }
            if (code >= 400) {
                throw new ServerFailure();
            }
            long total = connection.getContentLengthLong();
            if (total > MAX_DOWNLOAD_BYTES) {
                throw new UnsupportedFailure();
            }
            in = connection.getInputStream();
            copy(in, target, cancelled, listener, total, true);
        } catch (SocketTimeoutException e) {
            throw new TimeoutFailure();
        } catch (DownloadCancelledException e) {
            throw e;
        } catch (ExpiredFailure | ServerFailure | UnsupportedFailure e) {
            throw e;
        } catch (IOException e) {
            throw new NetworkFailure();
        } finally {
            closeQuietly(in);
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static void copy(InputStream in, File target,
            CancellationSignal cancelled, ProgressListener listener, long total,
            boolean network) throws IOException, TimeoutFailure, NetworkFailure,
            SourceFailure, StorageFailure {
        long bytes = 0L;
        long lastReport = 0L;
        try (OutputStream out = new BufferedOutputStream(new FileOutputStream(target))) {
            byte[] buffer = new byte[BUFFER_SIZE];
            while (true) {
                cancelled.throwIfCancelled();
                int read;
                try {
                    read = in.read(buffer);
                } catch (SocketTimeoutException e) {
                    throw new TimeoutFailure();
                } catch (IOException e) {
                    if (network) {
                        throw new NetworkFailure();
                    }
                    throw new SourceFailure();
                }
                if (read == -1) {
                    break;
                }
                if (read == 0) {
                    continue;
                }
                bytes += read;
                if (bytes > MAX_DOWNLOAD_BYTES) {
                    throw new NetworkFailure();
                }
                try {
                    out.write(buffer, 0, read);
                } catch (IOException e) {
                    throw new StorageFailure();
                }
                if (listener != null && bytes - lastReport >= PROGRESS_STEP) {
                    lastReport = bytes;
                    listener.onProgress(bytes, total);
                }
            }
        } catch (DownloadCancelledException e) {
            throw e;
        } catch (IOException e) {
            throw new StorageFailure();
        }
        cancelled.throwIfCancelled();
        if (listener != null) {
            listener.onProgress(bytes, total);
        }
    }

    private static void closeQuietly(InputStream stream) {
        if (stream == null) {
            return;
        }
        try {
            stream.close();
        } catch (IOException ignored) {
            // Nothing useful can be done with a close failure.
        }
    }

    private static void delete(File file) {
        if (file != null) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }

    private static final class TimeoutFailure extends Exception {
        private static final long serialVersionUID = 1L;
    }

    private static final class ExpiredFailure extends Exception {
        private static final long serialVersionUID = 1L;
    }

    private static final class ServerFailure extends Exception {
        private static final long serialVersionUID = 1L;
    }

    private static final class SourceFailure extends Exception {
        private static final long serialVersionUID = 1L;
    }

    private static final class NetworkFailure extends Exception {
        private static final long serialVersionUID = 1L;
    }

    private static final class StorageFailure extends Exception {
        private static final long serialVersionUID = 1L;
    }

    private static final class UnsupportedFailure extends Exception {
        private static final long serialVersionUID = 1L;
    }
}
