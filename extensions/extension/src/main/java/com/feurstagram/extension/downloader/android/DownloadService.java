package com.feurstagram.extension.downloader.android;

import android.app.Notification;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

/**
 * Keeps the download queue alive while it has work.
 *
 * <p>The queue runs on in-process worker threads; without a foreground
 * service, ColorOS and other aggressive skins reclaim the process in the
 * background and a long video dies silently. This service is the Android 14+
 * compatible answer: it is a declared {@code dataSync} foreground service,
 * started from the (visible) share activity, and it stops itself as soon as
 * the queue is idle. It performs no work of its own — the workers do — and it
 * references no Instagram class.
 *
 * <p>Process-death recovery does not depend on the restart: every accepted
 * item is mirrored to {@link PendingWorkStore} before dispatch, and the next
 * start replays that ledger. If the system restarts the service after a kill,
 * the queue resumes immediately; if the OEM refuses the restart, the work is
 * replayed the next time the user opens the app.
 *
 * <p>Known ColorOS limits, documented honestly: aggressive battery modes can
 * still delay or refuse the service restart, and Android 15 caps dataSync
 * foreground services at six hours per day. Users who hit either should allow
 * background activity for the app; that is a settings choice, not a
 * permission change.
 */
public final class DownloadService extends Service {

    private static final String TAG = "HmxDownloader";

    private HmxDownloader downloader;
    private boolean foreground;

    @Override
    public void onCreate() {
        super.onCreate();
        downloader = HmxDownloader.get(this);
        downloader.setIdleListener(new Runnable() {
            @Override
            public void run() {
                stopWhenIdle();
            }
        });
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForegroundSafe();
        downloader.start();
        stopWhenIdle();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        downloader.setIdleListener(null);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /**
     * Starts this service from a visible context. The share activity is the
     * only caller in M2; a background start would be refused on Android 12+.
     */
    static void ensureRunning(Context context) {
        if (context == null) {
            return;
        }
        Intent intent = new Intent(context, DownloadService.class);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        } catch (RuntimeException e) {
            // ForegroundServiceStartNotAllowedException and OEM variants:
            // the download continues in-process instead.
            Log.w(TAG, "could not start the download service", e);
        }
    }

    private void startForegroundSafe() {
        try {
            Notification notification = downloader.foregroundNotification();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(DownloadNotifier.PROGRESS_ID, notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(DownloadNotifier.PROGRESS_ID, notification);
            }
            foreground = true;
        } catch (RuntimeException e) {
            // A missing notification permission never blocks the service; the
            // system simply keeps the notification hidden until granted.
            Log.w(TAG, "could not enter the foreground", e);
        }
    }

    private void stopWhenIdle() {
        if (!downloader.isIdle()) {
            return;
        }
        if (foreground) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(Service.STOP_FOREGROUND_REMOVE);
                } else {
                    stopForeground(true);
                }
            } catch (RuntimeException ignored) {
                // Already stopped or the system refused the update.
            }
            foreground = false;
        }
        stopSelf();
    }
}
