package com.feurstagram.extension.downloader;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The HMX downloader: one engine behind the feed, story and reel entry points.
 *
 * A download is always started by the user, always local and never leaves the
 * device: the media is fetched from the URL the app itself uses, with no
 * third-party service in between, and it is written straight into the shared
 * media collection through MediaStore.
 *
 * The queue is bounded, only a few downloads run at once, a failed download
 * can be retried while the error is recoverable, and the same media is never
 * saved twice.
 */
public final class HmxDownloader {

    private static final String TAG = "HmxDownloader";

    /** How many downloads run at the same time. */
    public static final int MAX_CONCURRENT = 2;
    /** How many downloads may wait in the queue. */
    public static final int MAX_QUEUED = 20;
    /** The network timeout of a media fetch. */
    private static final int TIMEOUT_MS = 20000;

    private static final HmxDownloader INSTANCE = new HmxDownloader();

    private final Object lock = new Object();
    private final List<DownloadTask> queue = new ArrayList<>();
    private final DownloadHistory history = new DownloadHistory();
    private final DuplicateDetector duplicates = new DuplicateDetector();
    private final AtomicBoolean running = new AtomicBoolean();
    private ExecutorService workers;

    private HmxDownloader() {
    }

    public static HmxDownloader get() {
        return INSTANCE;
    }

    /** The tasks that are waiting or running, oldest first. */
    public List<DownloadTask> tasks() {
        synchronized (lock) {
            return new ArrayList<>(queue);
        }
    }

    /** The finished downloads, newest first. */
    public List<DownloadTask> history() {
        return history.entries();
    }

    /** The number of downloads that are waiting or running. */
    public int pending() {
        synchronized (lock) {
            int count = 0;
            for (DownloadTask task : queue) {
                if (!task.isFinished()) count++;
            }
            return count;
        }
    }

    /**
     * Queues a download.
     *
     * @param context the context the download runs under
     * @param item    what to download
     * @param owner   the account that posted it, used in the file name
     * @return the task, or null when the item cannot be downloaded
     */
    public DownloadTask enqueue(Context context, MediaItem item, String owner) {
        if (context == null || item == null) return null;
        if (item.url() == null || item.url().isEmpty()) return null;

        if (duplicates.isDuplicate(item)) {
            DownloadTask duplicate = new DownloadTask(item, owner, item.variant().label());
            duplicate.start(0);
            duplicate.fail(DownloadError.DUPLICATE);
            return duplicate;
        }

        DownloadTask task;
        synchronized (lock) {
            int waiting = 0;
            for (DownloadTask queued : queue) {
                if (!queued.isFinished()) waiting++;
            }
            if (waiting >= MAX_QUEUED) {
                Log.w(TAG, "the download queue is full");
                return null;
            }
            task = new DownloadTask(item, owner, item.variant().label());
            queue.add(task);
        }

        history.recordQueued(task);
        runNext(context);
        return task;
    }

    /** Puts a failed task back in the queue when the error was recoverable. */
    public boolean retry(Context context, DownloadTask task) {
        if (context == null || task == null || !task.retry()) return false;
        runNext(context);
        return true;
    }

    /** Cancels a task. */
    public void cancel(DownloadTask task) {
        if (task == null) return;
        task.cancel();
        history.recordFinished(task);
    }

    /** Forgets the history and the duplicate keys. */
    public void clearHistory() {
        history.clear();
        duplicates.clear();
    }

    /** Starts workers while there is work and fewer than MAX_CONCURRENT run. */
    private void runNext(Context context) {
        synchronized (lock) {
            if (workers == null) workers = Executors.newFixedThreadPool(MAX_CONCURRENT);
        }
        int active = 0;
        DownloadTask next = null;
        synchronized (lock) {
            for (DownloadTask task : queue) {
                if (task.state() == DownloadTask.State.RUNNING) active++;
            }
            if (active < MAX_CONCURRENT) {
                for (DownloadTask task : queue) {
                    if (task.state() == DownloadTask.State.QUEUED) {
                        next = task;
                        break;
                    }
                }
            }
        }
        if (next == null) return;
        final DownloadTask task = next;
        final Context app = context.getApplicationContext();
        workers.execute(() -> {
            try {
                run(app, task);
            } catch (Throwable t) {
                Log.w(TAG, "download failed", t);
                task.fail(DownloadError.NETWORK);
            } finally {
                history.recordFinished(task);
                runNext(app);
            }
        });
    }

    /** Fetches the media and writes it into the gallery. */
    private void run(Context context, DownloadTask task) {
        MediaItem item = task.item();
        File temporary = null;
        try {
            URL url = new URL(item.url());
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            connection.setInstanceFollowRedirects(true);
            // The CDN serves the media to any client that knows the URL; the
            // request stays on the device and carries no account information.
            connection.setRequestProperty("User-Agent", "Instagram/454.0.0.54.77 Android");
            connection.connect();

            int status = connection.getResponseCode();
            if (status == 403 || status == 410) {
                connection.disconnect();
                task.fail(DownloadError.EXPIRED);
                return;
            }
            if (status < 200 || status >= 300) {
                connection.disconnect();
                task.fail(DownloadError.SERVER);
                return;
            }

            long total = connection.getContentLengthLong();
            task.start(total);

            temporary = File.createTempFile("hmx-insta-", ".tmp", context.getCacheDir());
            try (InputStream input = connection.getInputStream();
                 OutputStream output = new FileOutputStream(temporary)) {
                byte[] buffer = new byte[131072];
                long done = 0;
                int read;
                while ((read = input.read(buffer)) > 0) {
                    if (task.isCancelled()) {
                        task.fail(DownloadError.CANCELLED);
                        return;
                    }
                    output.write(buffer, 0, read);
                    done += read;
                    task.progress(done, total);
                }
            } finally {
                connection.disconnect();
            }

            if (task.isCancelled()) {
                task.fail(DownloadError.CANCELLED);
                return;
            }

            String saved;
            if (item.isAudioOnly()) {
                saved = AudioExtractor.extract(context, temporary, item.fileName(item.owner()));
            } else {
                saved = MediaStoreWriter.write(context, temporary, item);
            }
            if (saved == null) {
                task.fail(DownloadError.STORAGE);
                return;
            }
            duplicates.markDownloaded(item);
            task.succeed(saved);
            Notifications.completed(context, item, task);
        } catch (java.io.FileNotFoundException e) {
            task.fail(DownloadError.EXPIRED);
        } catch (java.net.SocketTimeoutException e) {
            task.fail(DownloadError.NETWORK);
        } catch (java.io.IOException e) {
            task.fail(DownloadError.NETWORK);
        } catch (Throwable t) {
            Log.w(TAG, "unexpected download failure", t);
            task.fail(DownloadError.NETWORK);
        } finally {
            if (temporary != null && temporary.exists()) {
                // The temporary copy never outlives the download.
                temporary.delete();
            }
        }
    }
}
