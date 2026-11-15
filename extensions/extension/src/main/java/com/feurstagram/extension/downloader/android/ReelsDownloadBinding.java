package com.feurstagram.extension.downloader.android;

import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.RelativeLayout;
import com.feurstagram.extension.downloader.core.InContextAction;
import com.feurstagram.extension.downloader.core.MediaModelReader;
import com.feurstagram.extension.downloader.core.PlacementPolicy;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/** Only a visible, idle native pager and a collision-free native action bar qualify. */
public final class ReelsDownloadBinding {
    private ReelsDownloadBinding() { }

    public static void register(Object owner) {
        NativeUiTrace.armed(NativeUiTrace.Surface.REELS, owner);
        try {
            WeakReference<Object> reference = new WeakReference<Object>(owner);
            Handler main = new Handler(Looper.getMainLooper());
            main.post(new Runnable() {
                int attempts;
                @Override public void run() {
                    try {
                        Object pagerOwner = reference.get();
                        if (pagerOwner == null) return;
                        View pager = pagerView(pagerOwner);
                        NativeUiTrace.note(NativeUiTrace.Surface.REELS, "PAGER_ATTEMPT",
                                pager == null ? "NOT_FOUND" : "FOUND", pager);
                        if (pager == null || !pager.isAttachedToWindow()) {
                            if (++attempts < 30) {
                                main.postDelayed(this, 100);
                            } else {
                                NativeUiTrace.refused(NativeUiTrace.Surface.REELS,
                                        "PAGER_ATTACH_TIMEOUT", pager);
                            }
                            return;
                        }
                        NativeUiTrace.note(NativeUiTrace.Surface.REELS, "PAGER_FOUND",
                                "NONE", pager);
                        Method getter = null;
                        for (Method method : pagerOwner.getClass().getMethods()) {
                            if (method.getParameterTypes().length != 0
                                    || Modifier.isStatic(method.getModifiers())) continue;
                            if (MediaModelReader.hasSingleMediaField(method.getReturnType())) {
                                if (getter != null) {
                                    NativeUiTrace.refused(NativeUiTrace.Surface.REELS,
                                            "GETTER_AMBIGUOUS", pager);
                                    return;
                                }
                                getter = method;
                            }
                        }
                        if (getter != null) new Binding(pagerOwner, pager, getter).attach();
                        else NativeUiTrace.refused(NativeUiTrace.Surface.REELS,
                                "GETTER_MISSING", pager);
                    } catch (Throwable ignored) {
                        NativeUiTrace.failure(NativeUiTrace.Surface.REELS,
                                "REGISTER_EXCEPTION", ignored);
                    }
                }
            });
        } catch (Throwable ignored) {
            NativeUiTrace.failure(NativeUiTrace.Surface.REELS, "REGISTER_EXCEPTION", ignored);
        }
    }

    private static View pagerView(Object owner) throws IllegalAccessException {
        View found = null;
        for (Field field : owner.getClass().getFields()) {
            if (!Modifier.isStatic(field.getModifiers()) && field.getType().getName()
                    .equals("androidx.viewpager2.widget.ViewPager2")) {
                View value = (View) field.get(owner);
                if (value != null) {
                    if (found != null && found != value) return null;
                    found = value;
                }
            }
        }
        return found;
    }

    private static final class Binding implements ViewTreeObserver.OnPreDrawListener,
            View.OnAttachStateChangeListener {
        final WeakReference<Object> owner;
        final View pager;
        final Method getter;
        final Method scrollState;
        final Method currentItem;
        final View root;
        HmxDownloadIcon icon;
        Object page;
        int index = -1;
        long generation;
        boolean idle;
        int probeSignature;
        boolean probeDone;
        int actionBarId;

        Binding(Object owner, View pager, Method getter) throws NoSuchMethodException {
            this.owner = new WeakReference<Object>(owner);
            this.pager = pager;
            this.getter = getter;
            this.root = pager.getRootView();
            scrollState = pager.getClass().getMethod("getScrollState");
            currentItem = pager.getClass().getMethod("getCurrentItem");
        }

        void attach() {
            NativeUiTrace.note(NativeUiTrace.Surface.REELS, "LISTENER_ATTACHED",
                    "NONE", pager);
            pager.addOnAttachStateChangeListener(this);
            root.getViewTreeObserver().addOnPreDrawListener(this);
        }

        Object current() throws Exception {
            Object value = owner.get();
            return value == null ? null : getter.invoke(value);
        }

        @Override public boolean onPreDraw() {
            try {
                boolean nextIdle = pager.isShown() && ((Integer) scrollState.invoke(pager)) == 0;
                int nextIndex = (Integer) currentItem.invoke(pager);
                Object nextPage = nextIdle ? current() : null;
                if (nextPage != page || nextIndex != index || nextIdle != idle) {
                    generation++;
                    NativeUiTrace.note(NativeUiTrace.Surface.REELS, "PAGE",
                            nextIdle ? "IDLE" : "SCROLLING", nextPage);
                    if (nextPage != null) {
                        NativeUiTrace.note(NativeUiTrace.Surface.REELS, "PAGE_MODEL",
                                MediaModelReader.hasSingleMediaField(
                                        nextPage.getClass()) ? "WRAPPER" : "UNREADABLE",
                                nextPage);
                    }
                }
                page = nextPage;
                index = nextIndex;
                idle = nextIdle;
                int id = actionBarId;
                if (id == 0) {
                    id = root.getResources().getIdentifier("clips_viewer_action_bar", "id",
                            root.getContext().getPackageName());
                    actionBarId = id;
                }
                View anchor = id == 0 ? null : root.findViewById(id);
                NativeUiTrace.note(NativeUiTrace.Surface.REELS, "ANCHOR",
                        anchor == null ? "MISSING" : "FOUND", anchor);
                if (!(anchor instanceof ViewGroup) || !anchor.isShown()
                        || anchor.getRootView() != pager.getRootView()) {
                    hide("ANCHOR");
                    NativeUiTrace.refused(NativeUiTrace.Surface.REELS, "ANCHOR", anchor);
                    return true;
                }
                ViewGroup bar = (ViewGroup) anchor;
                if (PlacementProbe.container(bar)
                        == PlacementPolicy.Container.UNSUPPORTED) {
                    hide("ANCHOR_TYPE");
                    NativeUiTrace.refused(NativeUiTrace.Surface.REELS, "ANCHOR_TYPE", bar);
                    return true;
                }
                if (icon != null && icon.getParent() != bar) removeIcon();
                if (icon == null) {
                    icon = new HmxDownloadIcon(bar.getContext());
                    int size = Math.round(40 * bar.getResources().getDisplayMetrics().density);
                    bar.addView(icon, layoutParams(bar, size));
                    icon.setVisibility(View.INVISIBLE);
                    NativeUiTrace.created(NativeUiTrace.Surface.REELS, icon);
                    icon.setOnClickListener(v -> tap());
                }
                boolean enabled = DownloaderSwitch.isEnabled(bar.getContext());
                boolean slotFree = false;
                if (idle && page != null && enabled) {
                    slotFree = freeSlot(bar, icon);
                }
                boolean available = idle && page != null && enabled && slotFree;
                icon.setVisibility(available ? View.VISIBLE : View.INVISIBLE);
                if (available) {
                    NativeUiTrace.visible(NativeUiTrace.Surface.REELS, icon);
                } else {
                    NativeUiTrace.hidden(NativeUiTrace.Surface.REELS,
                            !idle ? "NOT_IDLE"
                                    : page == null ? "NO_PAGE"
                                    : !enabled ? "SWITCH_OFF"
                                    : "SLOT", icon);
                }
                if (idle && icon != null) {
                    int signature = PlacementProbe.signature(bar, icon);
                    if (!probeDone || signature != probeSignature) {
                        probeDone = true;
                        probeSignature = signature;
                        PlacementProbe.reportReels(bar, icon, slotFree);
                        if (!available) {
                            dumpBar(bar);
                        }
                    }
                }
            } catch (Throwable ignored) {
                hide("DRAW_EXCEPTION");
                NativeUiTrace.failure(NativeUiTrace.Surface.REELS, "DRAW_EXCEPTION", ignored);
            }
            return true;
        }

        private void tap() {
            try {
                final long captured = generation;
                final Object selected = current();
                final int selectedIndex = (Integer) currentItem.invoke(pager);
                NativeUiTrace.tap(NativeUiTrace.Surface.REELS, icon);
                if (!valid(captured, selected, selectedIndex)) {
                    NativeUiTrace.hidden(NativeUiTrace.Surface.REELS, "STALE_TAP", icon);
                    return;
                }
                com.feurstagram.extension.downloader.core.ObservedMedia media =
                        MediaModelReader.readWrapped(selected);
                NativeUiTrace.note(NativeUiTrace.Surface.REELS,
                        media == null ? "TAP_UNRESOLVED" : "TAP_RESOLVED", "NONE", icon);
                InContextDownloadSheet.showBound(icon.getContext(), media,
                        InContextAction.Surface.REELS, "reels-native-action-bar",
                        () -> valid(captured, selected, selectedIndex));
            } catch (Throwable ignored) {
                NativeUiTrace.failure(NativeUiTrace.Surface.REELS, "TAP_EXCEPTION", ignored);
            }
        }

        private boolean valid(long captured, Object selected, int selectedIndex) {
            try {
                return selected != null && captured == generation && pager.isAttachedToWindow()
                        && pager.isShown() && icon != null && icon.isShown()
                        && DownloaderSwitch.isEnabled(icon.getContext())
                        && ((Integer) scrollState.invoke(pager)) == 0
                        && ((Integer) currentItem.invoke(pager)) == selectedIndex && current() == selected;
            } catch (Throwable ignored) {
                return false;
            }
        }

        private void hide(String reason) {
            if (icon != null) {
                icon.setVisibility(View.INVISIBLE);
                NativeUiTrace.hidden(NativeUiTrace.Surface.REELS, reason, icon);
            }
        }

        private void removeIcon() {
            if (icon != null && icon.getParent() instanceof ViewGroup) {
                ((ViewGroup) icon.getParent()).removeView(icon);
            }
            icon = null;
        }

        @Override public void onViewAttachedToWindow(View view) { }

        @Override public void onViewDetachedFromWindow(View view) {
            NativeUiTrace.detached(NativeUiTrace.Surface.REELS, view);
            generation++;
            page = null;
            removeIcon();
            if (root.getViewTreeObserver().isAlive()) {
                root.getViewTreeObserver().removeOnPreDrawListener(this);
            }
            pager.removeOnAttachStateChangeListener(this);
        }
    }

    /**
     * Records the bar's children once per geometry signature.
     *
     * <p>Bounded to sixteen children. Each index is its own stage name so the
     * sampler does not skip a child, and the detail is structural only:
     * type, bounds, visibility, alpha and whether it is decorated.
     */
    private static void dumpBar(ViewGroup bar) {
        int count = Math.min(bar.getChildCount(), 16);
        for (int index = 0; index < count; index++) {
            View child = bar.getChildAt(index);
            if (child != null) {
                NativeUiTrace.note(NativeUiTrace.Surface.REELS,
                        "BAR_CHILD_" + index, String.valueOf(index), child);
            }
        }
    }

    /**
     * Layout params the anchor container can actually lay out.
     *
     * <p>Only the two proven container kinds are driven. A
     * {@code RelativeLayout} positions the control with rules, not gravity;
     * passing {@code FrameLayout.LayoutParams} to it would let the framework
     * convert the params and drop the gravity, silently moving the control.
     */
    private static ViewGroup.LayoutParams layoutParams(ViewGroup bar, int size) {
        if (bar instanceof FrameLayout) {
            return new FrameLayout.LayoutParams(size, size,
                    Gravity.END | Gravity.CENTER_VERTICAL);
        }
        RelativeLayout.LayoutParams params = new RelativeLayout.LayoutParams(size, size);
        params.addRule(RelativeLayout.ALIGN_PARENT_RIGHT, RelativeLayout.TRUE);
        params.addRule(RelativeLayout.CENTER_VERTICAL, RelativeLayout.TRUE);
        return params;
    }

    private static boolean freeSlot(ViewGroup bar, View icon) {
        if (icon.getWidth() <= 0 || icon.getHeight() <= 0) return false;
        Rect slot = new Rect();
        icon.getHitRect(slot);
        if (slot.left < bar.getPaddingLeft() || slot.top < bar.getPaddingTop()
                || slot.right > bar.getWidth() - bar.getPaddingRight()
                || slot.bottom > bar.getHeight() - bar.getPaddingBottom()) return false;
        for (int i = 0; i < bar.getChildCount(); i++) {
            View child = bar.getChildAt(i);
            if (child == icon || child.getVisibility() != View.VISIBLE) continue;
            Rect occupied = new Rect();
            child.getHitRect(occupied);
            if (Rect.intersects(slot, occupied)) {
                NativeUiTrace.note(NativeUiTrace.Surface.REELS, "SLOT_BLOCKER",
                        String.valueOf(i), child);
                return false;
            }
        }
        return true;
    }
}
