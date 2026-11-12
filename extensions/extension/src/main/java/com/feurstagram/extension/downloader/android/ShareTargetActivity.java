package com.feurstagram.extension.downloader.android;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

import com.feurstagram.extension.downloader.core.DownloadQueue;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * Entry point for {@code ACTION_SEND} and {@code ACTION_SEND_MULTIPLE}.
 *
 * <p>The activity is deliberately invisible: the share-target patch gives it
 * a translucent theme and keeps it out of Recents. It copies the shared
 * streams to private cache storage, enqueues one download per item and then
 * finishes. The ongoing notification reports progress; this activity never
 * touches an Instagram view or internal.
 */
public final class ShareTargetActivity extends Activity {

    private ExecutorService importer;
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

    private void handle(Intent intent) {
        if (handled) {
            return;
        }
        handled = true;
        final Context app = getApplicationContext();
        final List<ShareIntentParser.Payload> payloads;
        try {
            payloads = ShareIntentParser.parse(this, intent);
        } catch (IllegalArgumentException e) {
            showMessage("HMX Insta can only save shared photos and videos");
            return;
        }

        final ExecutorService executor = importer;
        if (executor == null) {
            showMessage("HMX Insta is not ready to receive shares");
            return;
        }
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
