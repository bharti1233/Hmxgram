package com.feurstagram.extension.downloader.android;

import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;
import com.feurstagram.extension.downloader.core.PlacementPolicy;

/**
 * Reads real views into {@link PlacementPolicy} nodes and records the shadow
 * decision.
 *
 * <p>M12 does not move anything: the M11 placement still decides whether the
 * control is shown. This class only describes the layout and logs what the
 * pure policy would decide, so a device capture says whether the policy agrees
 * with the code that is actually running.
 *
 * <p>Privacy: it reads class names, geometry, visibility, alpha, clickability
 * and whether a background exists. It never reads text, content descriptions,
 * tags, URLs or account values. The traversal is bounded in depth and width.
 */
public final class PlacementProbe {

    /** How deep the description may descend. */
    private static final int MAX_DEPTH = 6;

    /** How many children of one container are described. */
    private static final int MAX_CHILDREN = 32;

    /** The icon size the M11 placement uses, in dp. */
    private static final int ICON_DP = 40;

    private PlacementProbe() {
    }

    /** The pixel size the placement expects for its control. */
    public static int iconSizePx(View view) {
        return Math.round(ICON_DP * view.getResources().getDisplayMetrics().density);
    }

    /**
     * Records the Feed shadow decision.
     *
     * @param parent         the bookmark's parent, or {@code null}
     * @param bookmark       the native bookmark, or {@code null}
     * @param iconSizePx     the size the control needs, in pixels
     * @param legacyAccepted what the running M11 checks decided
     * @param legacyReason   the M11 branch, or {@code ACCEPT}
     */
    public static void reportFeed(View parent, View bookmark, int iconSizePx,
            boolean legacyAccepted, String legacyReason) {
        try {
            PlacementPolicy.Result result = PlacementPolicy.feed(
                    parent == null ? null : node(parent, false, 0),
                    bookmark == null ? null : node(bookmark, false, 0),
                    iconSizePx);
            NativeUiTrace.note(NativeUiTrace.Surface.FEED, "DECISION_LEGACY",
                    legacyAccepted ? "ACCEPT" : legacyReason, parent);
            NativeUiTrace.note(NativeUiTrace.Surface.FEED, "DECISION_POLICY",
                    result.code(), parent);
            NativeUiTrace.note(NativeUiTrace.Surface.FEED, "DECISION_AGREE",
                    result.placed() == legacyAccepted ? "AGREE" : "DISAGREE", bookmark);
        } catch (Throwable ignored) {
            NativeUiTrace.failure(NativeUiTrace.Surface.FEED, "PROBE_EXCEPTION", ignored);
        }
    }

    /**
     * The container kind the adapter can drive.
     *
     * <p>M11 assumed {@code FrameLayout}. The device capture proved the
     * native bar is a {@code RelativeLayout} subclass, so the adapter names
     * the kind it can lay out and refuses the rest instead of guessing.
     *
     * @param view the candidate container, or {@code null}
     */
    public static PlacementPolicy.Container container(View view) {
        if (view instanceof FrameLayout) {
            return PlacementPolicy.Container.FRAME;
        }
        if (view instanceof RelativeLayout) {
            return PlacementPolicy.Container.RELATIVE;
        }
        return PlacementPolicy.Container.UNSUPPORTED;
    }

    /**
     * Records the Reels shadow decision.
     *
     * @param bar            the native action bar
     * @param icon           the control, for its measured size
     * @param legacyAccepted what the running M11 free-slot check decided
     */
    public static void reportReels(ViewGroup bar, View icon, boolean legacyAccepted) {
        try {
            PlacementPolicy.Result result = PlacementPolicy.reels(
                    bar == null ? null : node(bar, true, 0),
                    icon == null ? 0 : icon.getWidth(),
                    icon == null ? 0 : icon.getHeight(),
                    bar == null ? PlacementPolicy.Container.UNSUPPORTED : container(bar));
            NativeUiTrace.note(NativeUiTrace.Surface.REELS, "SLOT_LEGACY",
                    legacyAccepted ? "AVAILABLE" : "BLOCKED", icon);
            NativeUiTrace.note(NativeUiTrace.Surface.REELS, "SLOT_POLICY",
                    result.code(), icon);
            if (!result.blocker().isEmpty()) {
                NativeUiTrace.note(NativeUiTrace.Surface.REELS, "SLOT_POLICY_BLOCKER",
                        result.blocker(), null);
            }
            NativeUiTrace.note(NativeUiTrace.Surface.REELS, "SLOT_AGREE",
                    result.placed() == legacyAccepted ? "AGREE" : "DISAGREE", icon);
        } catch (Throwable ignored) {
            NativeUiTrace.failure(NativeUiTrace.Surface.REELS, "PROBE_EXCEPTION", ignored);
        }
    }

    /** A shallow description of one view. */
    static PlacementPolicy.Node node(View view) {
        return node(view, false, 0);
    }

    /**
     * A cheap change detector for the Reels shadow probe.
     *
     * <p>The probe allocates a small tree, so it must not run on every frame
     * of a scroll. This signature covers the geometry that can change a slot
     * decision; the caller probes only when it differs.
     */
    public static int signature(ViewGroup bar, View icon) {
        int hash = 17;
        hash = 31 * hash + bar.getWidth();
        hash = 31 * hash + bar.getHeight();
        hash = 31 * hash + bar.getPaddingLeft();
        hash = 31 * hash + bar.getPaddingTop();
        hash = 31 * hash + bar.getPaddingRight();
        hash = 31 * hash + bar.getPaddingBottom();
        int count = Math.min(bar.getChildCount(), MAX_CHILDREN);
        hash = 31 * hash + count;
        for (int index = 0; index < count; index++) {
            View child = bar.getChildAt(index);
            if (child == null) {
                continue;
            }
            hash = 31 * hash + child.getLeft();
            hash = 31 * hash + child.getTop();
            hash = 31 * hash + child.getWidth();
            hash = 31 * hash + child.getHeight();
            hash = 31 * hash + child.getVisibility();
            hash = 31 * hash + Math.round(child.getAlpha() * 100f);
            hash = 31 * hash + (child.isClickable() ? 1 : 0);
        }
        hash = 31 * hash + (icon == null ? 0 : icon.getWidth());
        hash = 31 * hash + (icon == null ? 0 : icon.getHeight());
        return hash;
    }

    private static PlacementPolicy.Node node(View view, boolean deep, int depth) {
        PlacementPolicy.Builder builder = PlacementPolicy.node()
                .type(view.getClass().getName())
                .at(view.getLeft(), view.getTop())
                .size(view.getWidth(), view.getHeight())
                .visible(view.getVisibility() == View.VISIBLE)
                .attached(view.isAttachedToWindow())
                .alpha(view.getAlpha())
                .blocks(blocks(view))
                .padding(view.getPaddingLeft(), view.getPaddingTop(),
                        view.getPaddingRight(), view.getPaddingBottom());
        if (view instanceof LinearLayout) {
            LinearLayout linear = (LinearLayout) view;
            builder.axis(linear.getOrientation() == LinearLayout.HORIZONTAL
                            ? PlacementPolicy.Axis.HORIZONTAL
                            : PlacementPolicy.Axis.VERTICAL)
                    .orderedChildren(true)
                    .leftToRight(linear.getLayoutDirection() == View.LAYOUT_DIRECTION_LTR);
        } else {
            builder.axis(PlacementPolicy.Axis.NONE)
                    .orderedChildren(false)
                    .leftToRight(true);
        }
        if (deep && depth < MAX_DEPTH && view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            int count = Math.min(group.getChildCount(), MAX_CHILDREN);
            for (int index = 0; index < count; index++) {
                View child = group.getChildAt(index);
                if (child != null) {
                    builder.child(node(child, true, depth + 1));
                }
            }
        }
        return builder.build();
    }

    /**
     * Whether a view occupies its bounds as a real control.
     *
     * <p>A clickable view, a view that paints a background, text and images
     * all block. A plain {@code ViewGroup} with none of those is a wrapper:
     * the policy looks through it, but still tests its visible descendants.
     */
    private static boolean blocks(View view) {
        return view.isClickable() || view.getBackground() != null
                || view instanceof TextView || view instanceof ImageView;
    }
}
