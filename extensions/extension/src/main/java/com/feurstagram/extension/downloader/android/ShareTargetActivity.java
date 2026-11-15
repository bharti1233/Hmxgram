package com.feurstagram.extension.downloader.android;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.Toast;

import com.feurstagram.extension.downloader.core.DownloadQueue;
import com.feurstagram.extension.downloader.core.NotificationPermissionPolicy;
import com.feurstagram.extension.ghost.GhostFeature;
import com.feurstagram.extension.ghost.android.GhostState;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * Entry point for {@code ACTION_SEND} and {@code ACTION_SEND_MULTIPLE}.
 *
 * <p>The activity is deliberately invisible: the share-target patch gives it
 * a translucent theme and keeps it out of Recents. It asks for the Android 13
 * notification permission once per install (denial is respected and the save
 * continues either way), copies the shared streams to private cache storage,
 * enqueues one download per item and then finishes. The foreground service
 * reports progress; this activity never touches an Instagram view or internal.
 */
public final class ShareTargetActivity extends Activity {

    private static final String PREFS_NAME = "hmx_download_prefs";
    private static final String KEY_NOTIFICATION_ASKED =
            "notification_permission_asked";
    private static final int REQUEST_NOTIFICATIONS = 4103;

    private ExecutorService importer;
    private List<ShareIntentParser.Payload> pendingPayloads;
    private boolean handled;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        importer = Executors.newSingleThreadExecutor();
        handle(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handle(intent);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (importer != null) {
            importer.shutdownNow();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
            int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_NOTIFICATIONS) {
            return;
        }
        // The result is deliberately ignored here: a denial only means no
        // drawer notifications, never a failed save.
        List<ShareIntentParser.Payload> payloads = pendingPayloads;
        pendingPayloads = null;
        if (payloads == null) {
            finish();
            return;
        }
        importAll(payloads);
    }

    private void handle(Intent intent) {
        if (handled) {
            return;
        }
        handled = true;
        if (!DownloaderSwitch.isEnabled(this)) {
            // The switch is checked at the entry point, not only in the chip:
            // an external share must not start a download while it is off.
            showMessage("The HMX downloader is off. Turn it on in HMX Insta settings.");
            return;
        }
        final List<ShareIntentParser.Payload> payloads;
        try {
            payloads = ShareIntentParser.parse(this, intent);
        } catch (IllegalArgumentException e) {
            showMessage("HMX Insta can only save shared photos, videos and audio");
            return;
        }

        if (NotificationPermissionPolicy.Decision.REQUEST
                == notificationDecision()) {
            // One prompt per install, remembered even if the process is
            // recreated while the dialog is up.
            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean(KEY_NOTIFICATION_ASKED, true)
                    .apply();
            pendingPayloads = payloads;
            try {
                requestPermissions(
                        new String[]{Manifest.permission.POST_NOTIFICATIONS},
                        REQUEST_NOTIFICATIONS);
                // The import resumes in onRequestPermissionsResult, whatever
                // the user answers.
                return;
            } catch (RuntimeException e) {
                pendingPayloads = null;
                // Fall through and save without ever asking again.
            }
        }
        importAll(payloads);
    }

    private NotificationPermissionPolicy.Decision notificationDecision() {
        if (Build.VERSION.SDK_INT < NotificationPermissionPolicy.ANDROID_13) {
            return NotificationPermissionPolicy.Decision.SKIP;
        }
        boolean granted = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
        boolean askedBefore = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_NOTIFICATION_ASKED, false);
        return NotificationPermissionPolicy.decide(
                Build.VERSION.SDK_INT, granted, askedBefore);
    }

    private void importAll(final List<ShareIntentParser.Payload> payloads) {
        final Context app = getApplicationContext();
        final ExecutorService executor = importer;
        if (executor == null) {
            showMessage("HMX Insta is not ready to receive shares");
            return;
        }
        if (!DownloaderSwitch.isEnabled(this)) {
            // The switch may have been turned off while the notification
            // permission dialog was up.
            showMessage("The HMX downloader is off. Turn it on in HMX Insta settings.");
            return;
        }
        if (GhostState.isActiveSafely(app, GhostFeature.CONFIRM_BEFORE_SAVING)) {
            // The Ghost Mode control asks before a shared item is saved.
            confirmThenImport(payloads);
            return;
        }
        startImport(payloads, app, executor);
    }

    /** Asks the user before the shared items are saved. */
    private void confirmThenImport(final List<ShareIntentParser.Payload> payloads) {
        final Context app = getApplicationContext();
        final ExecutorService executor = importer;
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                new AlertDialog.Builder(ShareTargetActivity.this)
                        .setTitle("Save to the gallery?")
                        .setMessage(payloads.size() == 1
                                ? "Save this item to the gallery?"
                                : "Save " + payloads.size() + " items to the gallery?")
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Save",
                                new DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(DialogInterface dialog, int which) {
                                        startImport(payloads, app, executor);
                                    }
                                })
                        .show();
            }
        });
    }

    private void startImport(final List<ShareIntentParser.Payload> payloads,
            final Context app, final ExecutorService executor) {
        try {
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    int added = 0;
                    int duplicates = 0;
                    int failed = 0;
                    HmxDownloader downloader = HmxDownloader.get(app);
                    for (ShareIntentParser.Payload payload : payloads) {
                        try {
                            ShareImporter.Imported imported =
                                    ShareImporter.importOne(app, payload);
                            DownloadQueue.EnqueueResult result =
                                    downloader.enqueue(imported.item);
                            if (result.isAdded()) {
                                added++;
                            } else {
                                duplicates++;
                                ShareCacheProvider.deleteCopy(app, imported.file);
                            }
                        } catch (IOException e) {
                            failed++;
                        } catch (RuntimeException e) {
                            failed++;
                        }
                    }
                    showResult(added, duplicates, failed);
                }
            });
        } catch (RejectedExecutionException e) {
            showMessage("HMX Insta is shutting down");
        }
    }

    private void showResult(final int added, final int duplicates, final int failed) {
        final String message;
        if (added > 0 && duplicates == 0 && failed == 0) {
            message = added == 1 ? "Saving to the gallery" : "Saving " + added + " items";
        } else if (added == 0 && duplicates > 0 && failed == 0) {
            message = "Already saved";
        } else if (added == 0 && duplicates == 0) {
            message = "Could not open the shared item";
        } else {
            message = added + " saving, " + duplicates + " already saved, "
                    + failed + " failed";
        }
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(getApplicationContext(), message, Toast.LENGTH_SHORT).show();
                finish();
            }
        });
    }

    private void showMessage(final String message) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(getApplicationContext(), message, Toast.LENGTH_SHORT).show();
                finish();
            }
        });
    }
}
