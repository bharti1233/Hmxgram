package com.feurstagram.extension.downloader.android;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.widget.ImageView;
import com.feurstagram.extension.downloader.core.ObservedMedia;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Bounded display-only preview; never enters the download queue or history. */
final class ConfirmationPreview {
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    private volatile boolean closed;
    private volatile HttpURLConnection connection;
    private Future<?> task;

    void load(ImageView view, ObservedMedia media, Runnable ready) {
        String source = media.previewUrl();
        if (source == null) return;
        task = WORKER.submit(() -> {
            HttpURLConnection request = null;
            try {
                URL url = new URL(source);
                if (!"https".equalsIgnoreCase(url.getProtocol()) || url.getUserInfo() != null) return;
                request = (HttpURLConnection) url.openConnection();
                connection = request;
                request.setInstanceFollowRedirects(false);
                request.setConnectTimeout(5000);
                request.setReadTimeout(5000);
                if (closed || request.getResponseCode() != 200) return;
                String type = request.getContentType();
                if (type == null || !type.startsWith("image/")) return;
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                try (InputStream input = request.getInputStream()) {
                    byte[] buffer = new byte[8192];
                    int count;
                    while (!closed && (count = input.read(buffer)) != -1) {
                        if (bytes.size() + count > 4 * 1024 * 1024) return;
                        bytes.write(buffer, 0, count);
                    }
                }
                if (closed) return;
                byte[] data = bytes.toByteArray();
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(data, 0, data.length, options);
                if (options.outWidth <= 0 || options.outHeight <= 0) return;
                options.inSampleSize = 1;
                while (Math.max(options.outWidth, options.outHeight) / options.inSampleSize > 768) {
                    options.inSampleSize *= 2;
                }
                options.inJustDecodeBounds = false;
                Bitmap bitmap = BitmapFactory.decodeByteArray(data, 0, data.length, options);
                if (bitmap == null) return;
                view.post(() -> {
                    if (!closed && view.isAttachedToWindow()) {
                        view.setImageBitmap(bitmap);
                        ready.run();
                    } else bitmap.recycle();
                });
            } catch (Exception ignored) {
                // Keep the explicit unavailable label; never substitute other media.
            } finally {
                if (request != null) request.disconnect();
                connection = null;
            }
        });
    }

    void close() {
        closed = true;
        if (task != null) task.cancel(true);
        HttpURLConnection request = connection;
        if (request != null) request.disconnect();
    }
}
