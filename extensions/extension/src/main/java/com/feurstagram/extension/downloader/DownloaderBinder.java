package com.feurstagram.extension.downloader;

import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.LinearLayout;
import com.feurstagram.extension.downloader.android.DownloaderSwitch;
import com.feurstagram.extension.downloader.android.HmxDownloadIcon;
import com.feurstagram.extension.downloader.android.InContextDownloadSheet;
import com.feurstagram.extension.downloader.android.NativeUiTrace;
import com.feurstagram.extension.downloader.android.PlacementProbe;
import com.feurstagram.extension.downloader.core.InContextAction;
import com.feurstagram.extension.downloader.core.MediaModelReader;
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
            NativeUiTrace.armed(NativeUiTrace.Surface.FEED, root);
            if (installed) return;
            installed = true;
            DownloaderSwitch.addChangeListener(() -> new Handler(Looper.getMainLooper()).post(() -> {
                for (HmxDownloadIcon icon : ICONS) {
                    icon.setVisibility(DownloaderSwitch.isEnabled(icon.getContext())
                            ? View.VISIBLE : View.GONE);
                }
            }));
        } catch (Throwable ignored) {
            NativeUiTrace.failure(NativeUiTrace.Surface.FEED, "INSTALL_EXCEPTION", ignored);
        }
    }

    /** Receives only the holder and media model from the verified action-row binder. */
    public static void bindFeed(Object model, Object holder) {
        NativeUiTrace.note(NativeUiTrace.Surface.FEED, "BINDER", "NONE", holder);
        NativeUiTrace.note(NativeUiTrace.Surface.FEED, "MEDIA_MODEL",
                mediaReason(model), model);
        observeRow(holder, model);
    }

    /** Whether the bound model can yield media, without reading a URL. */
    private static String mediaReason(Object model) {
        if (model == null) {
            return "MODEL_NULL";
        }
        if (MediaModelReader.isMediaModel(model)) {
            return "MEDIA";
        }
        if (MediaModelReader.hasSingleMediaField(model.getClass())) {
            return "WRAPPER";
        }
        return "UNREADABLE";
    }

    public static void observeRow(Object holder, Object model) {
        try {
            if (holder == null || Looper.myLooper() != Looper.getMainLooper()) {
                NativeUiTrace.refused(NativeUiTrace.Surface.FEED, "THREAD_OR_HOLDER", holder);
                return;
            }
            NativeUiTrace.note(NativeUiTrace.Surface.FEED, "HOLDER", "NONE", holder);
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
                    if (bookmark != null && bookmark != candidate) {
                        NativeUiTrace.refused(NativeUiTrace.Surface.FEED,
                                "BOOKMARK_AMBIGUOUS", candidate);
                        PlacementProbe.reportFeed(parentOf(candidate), candidate,
                                PlacementProbe.iconSizePx(candidate), false,
                                "BOOKMARK_AMBIGUOUS");
                        return;
                    }
                    bookmark = candidate;
                }
            }
            if (bookmark == null) {
                NativeUiTrace.refused(NativeUiTrace.Surface.FEED, "BOOKMARK_MISSING", holder);
            } else {
                NativeUiTrace.note(NativeUiTrace.Surface.FEED, "BOOKMARK_FOUND",
                        "NONE", bookmark);
            }
            View parent = bookmark == null ? null : parentOf(bookmark);
            if (parent != null) {
                NativeUiTrace.note(NativeUiTrace.Surface.FEED, "PARENT", "NONE", parent);
            }
            if (bookmark == null || !(parent instanceof LinearLayout)) {
                NativeUiTrace.refused(NativeUiTrace.Surface.FEED, "PARENT", parent);
                PlacementProbe.reportFeed(parent, bookmark,
                        parent == null ? 0 : PlacementProbe.iconSizePx(parent),
                        false, "PARENT");
                if (parent instanceof ViewGroup)
                    NativeUiTrace.children("feed.parent", (ViewGroup) parent);
                return;
            }
            final LinearLayout row = (LinearLayout) parent;
            if (row.getOrientation() != LinearLayout.HORIZONTAL
                    || row.getLayoutDirection() != View.LAYOUT_DIRECTION_LTR) {
                NativeUiTrace.refused(NativeUiTrace.Surface.FEED, "ORIENTATION", row);
                PlacementProbe.reportFeed(row, bookmark,
                        PlacementProbe.iconSizePx(row), false, "ORIENTATION");
                return;
            }
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
                NativeUiTrace.created(NativeUiTrace.Surface.FEED, icon);
                ICONS.add(icon);
                final HmxDownloadIcon control = icon;
                control.setOnClickListener(view -> {
                    try {
                        if (!control.isAttachedToWindow() || !control.isShown()) return;
                        NativeUiTrace.tap(NativeUiTrace.Surface.FEED, control);
                        long generation = control.bindingGeneration();
                        com.feurstagram.extension.downloader.core.ObservedMedia selected = control.media();
                        NativeUiTrace.note(NativeUiTrace.Surface.FEED,
                                selected == null ? "TAP_UNRESOLVED" : "TAP_RESOLVED",
                                "NONE", control);
                        InContextDownloadSheet.showBound(control.getContext(), selected,
                                InContextAction.Surface.FEED, "feed-action-row",
                                () -> control.isAttachedToWindow() && control.isShown()
                                        && control.bindingGeneration() == generation);
                    } catch (Throwable failure) {
                        NativeUiTrace.failure(NativeUiTrace.Surface.FEED,
                                "TAP_EXCEPTION", failure);
                    }
                });
            }
            icon.bindModel(model, InContextAction.Surface.FEED, "feed-action-row");
            boolean enabled = DownloaderSwitch.isEnabled(row.getContext());
            icon.setVisibility(enabled ? View.VISIBLE : View.GONE);
            NativeUiTrace.note(NativeUiTrace.Surface.FEED, "BOUND", "NONE", icon);
            final HmxDownloadIcon control = icon;
            final View bookmarkView = bookmark;
            control.post(() -> {
                try {
                    boolean shown = control.isAttachedToWindow() && control.isShown()
                            && control.getWidth() > 0 && control.getHeight() > 0;
                    String reason = hiddenReason(control);
                    PlacementProbe.reportFeed(row, bookmarkView,
                            PlacementProbe.iconSizePx(row), true, "ACCEPT");
                    NativeUiTrace.note(NativeUiTrace.Surface.FEED, "ATTACH_RESULT",
                            shown ? "SHOWN" : reason, control);
                    if (shown) {
                        NativeUiTrace.visible(NativeUiTrace.Surface.FEED, control);
                    } else {
                        NativeUiTrace.hidden(NativeUiTrace.Surface.FEED, reason, control);
                    }
                } catch (Throwable failure) {
                    NativeUiTrace.failure(NativeUiTrace.Surface.FEED,
                            "ATTACH_EXCEPTION", failure);
                }
            });
        } catch (Throwable ignored) {
            NativeUiTrace.failure(NativeUiTrace.Surface.FEED, "EXCEPTION", ignored);
            // An unsupported host layout must not affect Instagram.
        }
    }

    /** The parent when it is a view, otherwise null. */
    private static View parentOf(View view) {
        ViewParent parent = view.getParent();
        return parent instanceof View ? (View) parent : null;
    }

    /** The most specific reason a control is not visible, for the trace. */
    private static String hiddenReason(View view) {
        if (!DownloaderSwitch.isEnabled(view.getContext())) {
            return "SWITCH_OFF";
        }
        if (!view.isAttachedToWindow()) {
            return "NOT_ATTACHED";
        }
        if (view.getWidth() <= 0 || view.getHeight() <= 0) {
            return "ZERO_SIZE";
        }
        if (view.getVisibility() != View.VISIBLE) {
            return "GONE";
        }
        if (!view.isShown()) {
            return "NOT_SHOWN";
        }
        return "UNKNOWN";
    }
}
