package com.feurstagram.extension.downloader;

import android.app.Activity;
import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageButton;

import com.feurstagram.extension.Config;
import android.app.Application;

import java.lang.ref.WeakReference;

/**
 * Puts the HMX download button where the user expects it.
 *
 * The button is added to Instagram's own views rather than drawn over them:
 * in the feed it goes into the post's action row, between "Share" and "Save",
 * and in the story and reels viewers it goes into the viewer's own top-right
 * controls. Nothing is drawn on top of Instagram's UI and no overlay window is
 * used.
 *
 * The views are found at runtime, by what they say rather than by a name that
 * changes with every Instagram release: the action row is recognised by the
 * content descriptions Instagram gives its buttons.
 */
public final class DownloaderInstaller {

    private static final String[] ACTION_ROW_MARKERS = {
            "share", "repost", "save", "comment",
    };

    private static WeakReference<Activity> watched = new WeakReference<>(null);
    private static boolean installed;

    private DownloaderInstaller() {
    }

    /** Starts watching the screens. Safe to call more than once. */
    public static void install() {
        if (installed) return;
        installed = true;
        Context context = Config.getAppContext();
        if (context == null) return;
        try {
            android.app.Application application =
                    (android.app.Application) context.getApplicationContext();
            application.registerActivityLifecycleCallbacks(
                    new android.app.Application.ActivityLifecycleCallbacks() {
                        @Override public void onActivityCreated(Activity a, android.os.Bundle b) {}
                        @Override public void onActivityStarted(Activity a) {}
                        @Override public void onActivityResumed(Activity a) { attach(a); }
                        @Override public void onActivityPaused(Activity a) {}
                        @Override public void onActivityStopped(Activity a) {}
                        @Override public void onActivitySaveInstanceState(Activity a, android.os.Bundle b) {}
                        @Override public void onActivityDestroyed(Activity a) {}
                    });
        } catch (Throwable ignored) {
            // Without a lifecycle callback the buttons simply do not appear.
        }
    }

    /** Watches one activity and adds the button when it can. */
    static void attach(Activity activity) {
        if (activity == null) return;
        watched = new WeakReference<>(activity);
        try {
            View decor = activity.getWindow().getDecorView();
            decor.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
                Activity current = watched.get();
                if (current == null || current.isFinishing()) return;
                try {
                    addButton(current, decor);
                } catch (Throwable ignored) {
                    // A screen we cannot read is simply left alone.
                }
            });
        } catch (Throwable ignored) {
            // No window, no button.
        }
    }

    /** Adds the HMX download button to the screen, once. */
    private static void addButton(Activity activity, View root) {
        View anchor = findActionRowAnchor(root);
        if (anchor == null) return;
        ViewGroup parent = anchor.getParent() instanceof ViewGroup
                ? (ViewGroup) anchor.getParent() : null;
        if (parent == null) return;

        // Already there? The layout runs again on every scroll.
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            if (child.getTag() instanceof String
                    && "hmx-insta-download".equals(child.getTag())) {
                return;
            }
        }

        ImageButton button = new ImageButton(activity);
        button.setTag("hmx-insta-download");
        button.setImageResource(android.R.drawable.stat_sys_download);
        button.setBackgroundColor(0x00000000);
        button.setContentDescription("HMX Download");
        button.setOnClickListener(v -> onTapped(activity));

        ViewGroup.LayoutParams params = anchor.getLayoutParams();
        int size = Math.max(1, anchor.getWidth());
        if (params != null && params.height > 0) {
            button.setLayoutParams(params);
        } else {
            button.setLayoutParams(new ViewGroup.LayoutParams(size, size));
        }
        parent.addView(button, parent.indexOfChild(anchor) + 1);
    }

    /**
     * The view the HMX download button is placed after: the last of Instagram's
     * own action buttons (share, repost, comment) that is on screen.
     */
    private static View findActionRowAnchor(View root) {
        if (root == null) return null;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findActionRowAnchor(group.getChildAt(i));
                if (found != null) return found;
            }
            return null;
        }
        CharSequence description = root.getContentDescription();
        if (description == null) return null;
        String text = description.toString().toLowerCase(java.util.Locale.US);
        for (String marker : ACTION_ROW_MARKERS) {
            if (text.contains(marker)) return root;
        }
        return null;
    }

    /** Opens the download menu for the media that is on screen. */
    private static void onTapped(Activity activity) {
        if (activity == null) return;
        MediaRegistry.Entry entry = MediaRegistry.get().latest();
        if (entry == null) {
            android.widget.Toast.makeText(activity,
                    "HMX Insta is still loading this media", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        MediaItem.Offer offer = Surface.forActivity(activity).resolve(entry.model());
        DownloadMenu.show(activity, offer, MediaResolver.owner(entry.model()));
    }
}
