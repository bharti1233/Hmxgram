package com.feurstagram.extension.downloader.android;

import android.app.Dialog;
import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.feurstagram.extension.Settings;
import com.feurstagram.extension.downloader.core.DownloadQueue;
import com.feurstagram.extension.downloader.core.InContextAction;
import com.feurstagram.extension.downloader.core.ObservedMedia;

/**
 * The confirmation sheet behind the in-context chip.
 *
 * <p>Confirmation is not a formality. The observation is the media Instagram
 * parsed, not a binding to the specific row under the chip, so the sheet names
 * what will be saved and lets the user cancel. A carousel is shown as its
 * usable item count; the sheet then queues exactly those children.
 *
 * <p>Queueing goes through the same {@link HmxDownloader} the downloader
 * screen uses, so it inherits duplicate detection, the persisted queue, the
 * foreground service, progress/completion notifications and the verified
 * MediaStore publish. Nothing here downloads, writes or reads a file itself.
 */
final class InContextDownloadSheet {

    private InContextDownloadSheet() {
    }

    static void show(Context context, ObservedMedia media, long ageMs,
            InContextAction.Surface surface) {
        if (context == null || media == null) {
            return;
        }
        if (!DownloaderSwitch.isEnabled(context)) {
            Toast.makeText(context,
                    "The HMX downloader is off. Turn it on in HMX Insta settings.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        try {
            final Dialog dialog = new Dialog(context);
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

            FrameLayout frame = Settings.cardFrame(context);
            LinearLayout card = Settings.addCard(context, frame);

            TextView title = new TextView(context);
            title.setText(InContextAction.title(media));
            Settings.titleLarge(title);
            card.addView(title);

            final int count = InContextAction.targets(media).size();

            ScrollView bodyScroll = new ScrollView(context);
            bodyScroll.setVerticalScrollBarEnabled(false);
            TextView body = new TextView(context);
            body.setText(InContextAction.detail(media, ageMs, surface)
                    + "\n\nSaving " + count + (count == 1 ? " item" : " items")
                    + ". Progress and the result appear in the HMX download notification,"
                    + " and the same queue and saved history are in HMX Insta settings →"
                    + " Downloader. Saved files use the same HMX downloader folder as that"
                    + " screen.");
            Settings.body(body);
            body.setPadding(0, Settings.dp(context, 10), 0, 0);
            bodyScroll.addView(body);
            card.addView(bodyScroll, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            Settings.boundToFrame(frame, card, bodyScroll);

            LinearLayout buttons = new LinearLayout(context);
            buttons.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams buttonsLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            buttonsLp.setMargins(0, Settings.dp(context, 24), 0, 0);
            card.addView(buttons, buttonsLp);

            Button cancel = Settings.makeButton(
                    context, "Cancel", 0, Settings.ON_SURFACE, false);
            cancel.setBackground(Settings.outlined(context, Settings.OUTLINE_VARIANT));
            cancel.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    dialog.dismiss();
                }
            });
            buttons.addView(cancel, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            View spacer = new View(context);
            buttons.addView(spacer, new LinearLayout.LayoutParams(Settings.dp(context, 8), 1));

            Button download = Settings.makeButton(context,
                    count == 1 ? "Download" : "Download " + count + " items",
                    Settings.PRIMARY, Settings.ON_PRIMARY, true);
            download.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    dialog.dismiss();
                    enqueue(view.getContext(), media);
                }
            });
            buttons.addView(download, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            dialog.setContentView(frame);
            dialog.setCanceledOnTouchOutside(true);
            Settings.styleWindow(dialog, 0, 0.6f);
            dialog.show();
        } catch (Throwable failure) {
            // A sheet that cannot be shown must never crash Instagram.
            Toast.makeText(context, "HMX could not open the download action",
                    Toast.LENGTH_LONG).show();
        }
    }

    /** Queues the confirmed items through the existing engine. */
    private static void enqueue(Context context, ObservedMedia media) {
        if (!DownloaderSwitch.isEnabled(context)) {
            Toast.makeText(context,
                    "The HMX downloader is off. Turn it on in HMX Insta settings.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        int added = 0;
        int duplicates = 0;
        int failed = 0;
        try {
            HmxDownloader downloader = HmxDownloader.get(context.getApplicationContext());
            for (ObservedMedia target : InContextAction.targets(media)) {
                try {
                    DownloadQueue.EnqueueResult result = downloader.enqueue(target.toMediaItem());
                    if (result.isAdded()) {
                        added++;
                    } else if (result.status() == DownloadQueue.EnqueueStatus.DUPLICATE) {
                        duplicates++;
                    } else {
                        failed++;
                    }
                } catch (RuntimeException e) {
                    failed++;
                }
            }
        } catch (RuntimeException e) {
            failed = Math.max(1, InContextAction.targets(media).size());
        }
        Toast.makeText(context,
                InContextAction.resultMessage(added, duplicates, failed),
                Toast.LENGTH_LONG).show();
    }
}
