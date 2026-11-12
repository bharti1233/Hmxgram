package com.feurstagram.extension.downloader;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.feurstagram.extension.Settings;

/**
 * The contextual download menu.
 *
 * It shows exactly what can be done with the media on screen: the photo, every
 * item of a carousel, the video, its audio track and its cover. A variant that
 * the media does not offer is not offered here either — the menu says why
 * instead of pretending.
 */
public final class DownloadMenu {

    private DownloadMenu() {
    }

    /**
     * Shows the menu for one piece of media.
     *
     * @param context the context to show the menu in
     * @param offer   what the media offers
     * @param owner   the account that posted it
     */
    public static void show(Context context, MediaItem.Offer offer, String owner) {
        if (context == null || offer == null) return;

        try {
            Dialog dialog = new Dialog(context);
            dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);

            android.widget.FrameLayout frame = Settings.cardFrame(context);
            LinearLayout card = Settings.addCard(context, frame);

            TextView title = new TextView(context);
            title.setText("HMX Download");
            Settings.titleLarge(title);
            card.addView(title);

            TextView subtitle = new TextView(context);
            subtitle.setText(offer.items().isEmpty()
                    ? "Nothing to download here"
                    : offer.items().size() + " item(s) available");
            Settings.body(subtitle);
            subtitle.setPadding(0, Settings.dp(context, 4), 0, 0);
            card.addView(subtitle);

            for (MediaItem item : offer.items()) {
                String label = labelFor(item, offer);
                Button button = Settings.makeButton(context, label,
                        Settings.SURFACE_CONTAINER_HIGH, Settings.ON_SURFACE, true);
                button.setOnClickListener(v -> {
                    dialog.dismiss();
                    start(context, item, owner);
                });
                card.addView(button, margins(context, ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 10));
            }

            for (String reason : offer.unavailable().values()) {
                TextView note = new TextView(context);
                note.setText(reason);
                Settings.body(note);
                note.setPadding(0, Settings.dp(context, 10), 0, 0);
                card.addView(note);
            }

            Button close = Settings.makeButton(context, "Close", 0, Settings.ON_SURFACE, false);
            close.setBackground(Settings.outlined(context, Settings.OUTLINE_VARIANT));
            close.setOnClickListener(v -> dialog.dismiss());
            card.addView(close, margins(context, ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 16));

            dialog.setContentView(frame);
            dialog.setCanceledOnTouchOutside(true);
            Settings.styleWindow(dialog, 0, 0.6f);
            dialog.show();
        } catch (Throwable ignored) {
            Toast.makeText(context, "The download menu is unavailable here", Toast.LENGTH_LONG).show();
        }
    }

    /** Queues a download and says what happened. */
    private static void start(Context context, MediaItem item, String owner) {
        DownloadTask task = HmxDownloader.get().enqueue(context, item, owner);
        if (task == null) {
            Toast.makeText(context, "The download queue is full", Toast.LENGTH_LONG).show();
            return;
        }
        if (DownloadError.DUPLICATE.equals(task.error())) {
            Toast.makeText(context, DownloadError.message(DownloadError.DUPLICATE), Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(context, item.variant().label() + " started", Toast.LENGTH_SHORT).show();
    }

    /** The label of one item, with the carousel position when there is one. */
    private static String labelFor(MediaItem item, MediaItem.Offer offer) {
        if (offer.items().size() > 1 && item.kind() == MediaKind.PHOTO) {
            int index = offer.items().indexOf(item) + 1;
            return item.variant().label() + " " + index + " of " + offer.items().size();
        }
        return item.variant().label();
    }

    private static LinearLayout.LayoutParams margins(Context context, int width, int height, int topDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(width, height);
        params.setMargins(0, Settings.dp(context, topDp), 0, 0);
        return params;
    }
}
