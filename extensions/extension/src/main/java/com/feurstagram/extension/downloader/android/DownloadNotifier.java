package com.feurstagram.extension.downloader.android;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

/**
 * User-visible progress and completion notifications.
 *
 * <p>One low-importance ongoing notification aggregates progress; one final
 * notification reports how many items were saved or failed. Notification
 * content contains only counts and the word "download": never a URL, a token,
 * a cookie, an account name or an Instagram internal.
 *
 * <p>If the user denied notifications (Android 13+), every call is a no-op and
 * the save still completes.
 */
final class DownloadNotifier {

    private static final String CHANNEL_ID = "hmx_downloads";
    private static final int PROGRESS_ID = 4101;
    private static final int RESULT_ID = 4102;

    private final Context context;
    private final NotificationManager manager;

    DownloadNotifier(Context context) {
        this.context = context.getApplicationContext();
        this.manager = (NotificationManager)
                this.context.getSystemService(Context.NOTIFICATION_SERVICE);
        createChannel();
    }

    void notifyProgress(int active) {
        if (!canNotify() || manager == null) {
            return;
        }
        if (active <= 0) {
            manager.cancel(PROGRESS_ID);
            return;
        }
        Notification.Builder builder = newBuilder();
        if (builder == null) {
            return;
        }
        builder.setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("HMX Insta")
                .setContentText(active == 1
                        ? "Saving one item"
                        : "Saving " + active + " items")
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setProgress(0, 0, true)
                .setCategory(Notification.CATEGORY_PROGRESS);
        Intent launch = launchIntent();
        if (launch != null) {
            builder.setContentIntent(pendingIntent(launch));
        }
        try {
            manager.notify(PROGRESS_ID, builder.build());
        } catch (SecurityException ignored) {
            // Permission was revoked between the check and the call.
        }
    }

    void notifyResult(int saved, int failed) {
        if (!canNotify() || manager == null) {
            return;
        }
        manager.cancel(PROGRESS_ID);
        if (saved <= 0 && failed <= 0) {
            return;
        }
        Notification.Builder builder = newBuilder();
        if (builder == null) {
            return;
        }
        String title;
        String text;
        if (failed == 0) {
            title = "Saved";
            text = saved == 1 ? "1 item saved to the gallery"
                    : saved + " items saved to the gallery";
        } else if (saved == 0) {
            title = "Download failed";
            text = failed == 1 ? "1 item could not be saved"
                    : failed + " items could not be saved";
        } else {
            title = "Download finished";
            text = saved + " saved, " + failed + " failed";
        }
        builder.setSmallIcon(failed == 0
                        ? android.R.drawable.stat_sys_download_done
                        : android.R.drawable.stat_notify_error)
                .setContentTitle(title)
                .setContentText(text)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_STATUS);
        Intent launch = launchIntent();
        if (launch != null) {
            builder.setContentIntent(pendingIntent(launch));
        }
        try {
            manager.notify(RESULT_ID, builder.build());
        } catch (SecurityException ignored) {
            // The user keeps notifications disabled; the save is unaffected.
        }
    }

    private void createChannel() {
        if (manager == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "Downloads", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Gallery saves started by HMX Insta");
        channel.setShowBadge(false);
        channel.enableVibration(false);
        channel.setSound(null, null);
        manager.createNotificationChannel(channel);
    }

    private Notification.Builder newBuilder() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return new Notification.Builder(context, CHANNEL_ID);
        }
        return new Notification.Builder(context);
    }

    private Intent launchIntent() {
        return context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
    }

    private PendingIntent pendingIntent(Intent launch) {
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getActivity(context, 0, launch, flags);
    }

    private boolean canNotify() {
        if (Build.VERSION.SDK_INT >= 33
                && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        return true;
    }
}
