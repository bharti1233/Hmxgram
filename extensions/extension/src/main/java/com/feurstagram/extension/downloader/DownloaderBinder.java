package com.feurstagram.extension.downloader;

import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import com.feurstagram.extension.downloader.android.DownloaderSwitch;
import com.feurstagram.extension.downloader.android.HmxDownloadIcon;
import com.feurstagram.extension.downloader.android.InContextDownloadSheet;
import com.feurstagram.extension.downloader.core.InContextAction;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/** UI hooks fail closed and never propagate a failure into the host binder. */
public final class DownloaderBinder {
    private static final Set<HmxDownloadIcon> ICONS = Collections.newSetFromMap(
            new WeakHashMap<HmxDownloadIcon, Boolean>());
    private static boolean installed;

    private DownloaderBinder() { }

    public static void install(ViewGroup root) {
        try {
            if (installed) return;
            installed = true;
            DownloaderSwitch.addChangeListener(() -> new Handler(Looper.getMainLooper()).post(() -> {
                for (HmxDownloadIcon icon : ICONS) {
                    icon.setVisibility(DownloaderSwitch.isEnabled(icon.getContext())
                            ? View.VISIBLE : View.GONE);
                }
            }));
        } catch (Throwable ignored) { }
    }

    /** Receives only the holder and media model from the verified action-row binder. */
    public static void bindFeed(Object model, Object holder) {
        observeRow(holder, model);
    }

    public static void observeRow(Object holder, Object model) {
        try {
            if (holder == null || Looper.myLooper() != Looper.getMainLooper()) return;
            View bookmark = null;
            for (Field field : holder.getClass().getFields()) {
                if (Modifier.isStatic(field.getModifiers())
                        || !View.class.isAssignableFrom(field.getType())) continue;
                View view = (View) field.get(holder);
                if (view == null) continue;
                int id = view.getResources().getIdentifier("row_feed_button_save", "id",
                        view.getContext().getPackageName());
                View candidate = id == 0 ? null : view.findViewById(id);
                if (candidate != null) {
                    if (bookmark != null && bookmark != candidate) return;
                    bookmark = candidate;
                }
            }
            if (bookmark == null || !(bookmark.getParent() instanceof LinearLayout)) return;
            LinearLayout row = (LinearLayout) bookmark.getParent();
            if (row.getOrientation() != LinearLayout.HORIZONTAL
                    || row.getLayoutDirection() != View.LAYOUT_DIRECTION_LTR) return;
            install(row);
            HmxDownloadIcon icon = null;
            for (int i = 0; i < row.getChildCount(); i++) {
                if (row.getChildAt(i) instanceof HmxDownloadIcon) {
                    icon = (HmxDownloadIcon) row.getChildAt(i);
                    break;
                }
            }
            if (icon == null) {
                icon = new HmxDownloadIcon(row.getContext());
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                        (LinearLayout.LayoutParams) bookmark.getLayoutParams());
                params.weight = 0f;
                params.width = Math.round(40 * row.getResources().getDisplayMetrics().density);
                row.addView(icon, row.indexOfChild(bookmark), params);
                ICONS.add(icon);
                final HmxDownloadIcon control = icon;
                control.setOnClickListener(view -> {
                    try {
                        if (!control.isAttachedToWindow() || !control.isShown()) return;
                        long generation = control.bindingGeneration();
                        InContextDownloadSheet.showBound(control.getContext(), control.media(),
                                InContextAction.Surface.FEED, "feed-action-row",
                                () -> control.isAttachedToWindow() && control.isShown()
                                        && control.bindingGeneration() == generation);
                    } catch (Throwable ignored) { }
                });
            }
            icon.bindModel(model, InContextAction.Surface.FEED, "feed-action-row");
            icon.setVisibility(DownloaderSwitch.isEnabled(row.getContext())
                    ? View.VISIBLE : View.GONE);
        } catch (Throwable ignored) {
            // An unsupported host layout must not affect Instagram.
        }
    }
}
