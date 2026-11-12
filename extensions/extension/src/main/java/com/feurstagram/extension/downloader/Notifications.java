package com.feurstagram.extension.downloader;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/**
 * The download notifications: the ongoing one while a download runs and the
 * one that says it finished.
 *
 * The notification only carries the name of the account the media came from —
 * no URL, no identifier and nothing about the account the user is signed in
 * with.
 */
final class Notifications {

    private static final String CHANNEL_ID = "hmx_insta_downloads";
    private static final int ONGOING_ID = 0x484D5801;
    private static final int DONE_ID = 0x484D5802;

    private Notifications() {
    }

    /** The notification shown while a download runs. */
    static void progress(Context context, DownloadTask task) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) return;
        channel(manager);

        Notification.Builder builder = builder(context)
                .setContentTitle("Downloading with HMX Insta")
                .setContentText(task.label())
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setOngoing(true)
                .setOnlyAlertOnce(true);
        if (task.bytesTotal() > 0) {
            builder.setProgress(1000, task.progress() * 10, false);
        } else {
            builder.setProgress(1000, 0, true);
        }
        manager.notify(ONGOING_ID, builder.build());
    }

    /** The notification shown when a download finished or failed. */
    static void completed(Context context, MediaItem item, DownloadTask task) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) return;
        channel(manager);

        String text = task.state() == DownloadTask.State.DONE
                ? item.variant().label() + " saved to your gallery"
                : DownloadError.message(task.error());
        Notification.Builder builder = builder(context)
                .setContentTitle(task.state() == DownloadTask.State.DONE
                        ? "HMX Insta" : "HMX Insta download failed")
                .setContentText(text)
                .setSmallIcon(task.state() == DownloadTask.State.DONE
                        ? android.R.drawable.stat_sys_download_done
                        : android.R.drawable.stat_notify_error)
                .setAutoCancel(true);
        manager.notify(DONE_ID, builder.build());
        manager.cancel(ONGOING_ID);
    }

    private static void channel(NotificationManager manager) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                "HMX Insta downloads", NotificationManager.IMPORTANCE_LOW);
        channel.setShowBadge(false);
        manager.createNotificationChannel(channel);
    }

    private static Notification.Builder builder(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return new Notification.Builder(context, CHANNEL_ID);
        }
        return new Notification.Builder(context);
    }
}
