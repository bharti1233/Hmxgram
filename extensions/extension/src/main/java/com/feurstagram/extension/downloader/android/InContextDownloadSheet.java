package com.feurstagram.extension.downloader.android;

import android.app.Dialog;
import android.content.Context;
import android.util.Log;
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
import com.feurstagram.extension.downloader.core.DownloadRequestLog;
import com.feurstagram.extension.downloader.core.ExplicitDownloadRequest;
import com.feurstagram.extension.downloader.core.InContextAction;
import com.feurstagram.extension.downloader.core.ObservedMedia;

import java.util.List;

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
public final class InContextDownloadSheet {

    private InContextDownloadSheet() {
    }

    public static void showBound(Context context, ObservedMedia media,
            InContextAction.Surface surface, String source,
            java.util.function.BooleanSupplier bindingValid) {
        if (context == null) return;
        if (ExplicitDownloadRequest.needsCarouselChoice(media)
                && DownloaderSwitch.isEnabled(context)) {
            String[] choices = new String[media.children().size() + 1];
            for (int i = 0; i < media.children().size(); i++) {
                choices[i] = "Item " + (i + 1) + " · " + InContextAction.title(media.children().get(i));
            }
            choices[choices.length - 1] = "All available items";
            new android.app.AlertDialog.Builder(context)
                    .setTitle("Choose carousel items")
                    .setItems(choices, (dialog, index) -> showSelection(context, media,
                            surface, source, index, index == choices.length - 1, bindingValid))
                    .setNegativeButton("Cancel", null).show();
            return;
        }
        showSelection(context, media, surface, source, -1, false, bindingValid);
    }

    private static void showSelection(Context context, ObservedMedia media,
            InContextAction.Surface surface, String source, int itemIndex, boolean allItems,
            java.util.function.BooleanSupplier bindingValid) {
        if (context == null) {
            return;
        }
        final ExplicitDownloadRequest request = ExplicitDownloadRequest.evaluateBound(
                media, surface, DownloaderSwitch.isEnabled(context),
                System.currentTimeMillis(), itemIndex, allItems);
        Log.i("HmxDownloader", DownloadRequestLog.pending(source, surface, media, request));
        if (!request.isReady()) {
            Toast.makeText(context,
                    request.message() == null
                            ? "HMX could not identify this media."
                            : request.message(),
                    Toast.LENGTH_LONG).show();
            return;
        }
        final List<ObservedMedia> targets = request.targets();
        try {
            final Dialog dialog = new Dialog(context);
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

            FrameLayout frame = Settings.cardFrame(context);
            LinearLayout card = Settings.addCard(context, frame);

            TextView title = new TextView(context);
            title.setText(InContextAction.title(media));
            Settings.titleLarge(title);
            card.addView(title);

            android.widget.ImageView preview = new android.widget.ImageView(context);
            preview.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
            preview.setContentDescription("Preview of selected " + InContextAction.title(targets.get(0)));
            card.addView(preview, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, Settings.dp(context, 180)));
            TextView previewStatus = new TextView(context);
            previewStatus.setText("Preview unavailable. Cancel if you cannot identify this media.");
            Settings.body(previewStatus);
            card.addView(previewStatus);
            ConfirmationPreview previewLoader = new ConfirmationPreview();
            dialog.setOnDismissListener(ignored -> previewLoader.close());

            final int count = targets.size();

            ScrollView bodyScroll = new ScrollView(context);
            bodyScroll.setVerticalScrollBarEnabled(false);
            TextView body = new TextView(context);
            body.setText(InContextAction.title(targets.get(0))
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
                    if (!view.isEnabled()) return;
                    view.setEnabled(false);
                    dialog.dismiss();
                    if (!bindingValid.getAsBoolean()) {
                        Toast.makeText(context, ExplicitDownloadRequest.UNRESOLVED,
                                Toast.LENGTH_LONG).show();
                        return;
                    }
                    ExplicitDownloadRequest confirmed = ExplicitDownloadRequest.evaluateBound(
                            media, surface, DownloaderSwitch.isEnabled(context),
                            System.currentTimeMillis(), itemIndex, allItems);
                    if (confirmed.isReady()) {
                        enqueue(view.getContext(), media, surface, source, confirmed);
                    } else {
                        Toast.makeText(context, confirmed.message(), Toast.LENGTH_LONG).show();
                    }
                }
            });
            buttons.addView(download, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            dialog.setContentView(frame);
            dialog.setCanceledOnTouchOutside(true);
            Settings.styleWindow(dialog, 0, 0.6f);
            dialog.show();
            previewLoader.load(preview, targets.get(0), () -> previewStatus.setText(
                    count == 1 ? "Selected media" : "First of " + count + " selected items"));
        } catch (Throwable failure) {
            // A sheet that cannot be shown must never crash Instagram.
            Toast.makeText(context, "HMX could not open the download action",
                    Toast.LENGTH_LONG).show();
        }
    }

    /** Queues exactly the confirmed items through the existing engine. */
    private static void enqueue(Context context, ObservedMedia media,
            InContextAction.Surface surface, String source, ExplicitDownloadRequest request) {
        List<ObservedMedia> targets = request.targets();
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
            for (ObservedMedia target : targets) {
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
            failed = Math.max(1, targets.size());
        }
        Log.i("HmxDownloader", DownloadRequestLog.entry(source, surface, media, request,
                added, duplicates, failed));
        Toast.makeText(context,
                InContextAction.resultMessage(added, duplicates, failed),
                Toast.LENGTH_LONG).show();
    }
}
