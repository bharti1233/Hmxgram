package com.feurstagram.extension.downloader.android;

import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import com.feurstagram.extension.downloader.core.InContextAction;
import com.feurstagram.extension.downloader.core.MediaModelReader;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/** Only a visible, idle native pager and a collision-free native action bar qualify. */
public final class ReelsDownloadBinding {
    private ReelsDownloadBinding() { }

    public static void register(Object owner) {
        NativeUiTrace.event("reels.hook", owner);
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
                        NativeUiTrace.event("reels.pager", pager);
                        if (pager == null || !pager.isAttachedToWindow()) {
                            if (++attempts < 30) main.postDelayed(this, 100);
                            else NativeUiTrace.event("reels.reject.attach-timeout", pager);
                            return;
                        }
                        Method getter = null;
                        for (Method method : pagerOwner.getClass().getMethods()) {
                            if (method.getParameterTypes().length != 0
                                    || Modifier.isStatic(method.getModifiers())) continue;
                            if (MediaModelReader.hasSingleMediaField(method.getReturnType())) {
                                if (getter != null) {
                                    NativeUiTrace.event("reels.reject.ambiguous-getter", pager);
                                    return;
                                }
                                getter = method;
                            }
                        }
                        if (getter != null) new Binding(pagerOwner, pager, getter).attach();
                        else NativeUiTrace.event("reels.reject.no-getter", pager);
                    } catch (Throwable ignored) { NativeUiTrace.event("reels.register.exception", ignored); }
                }
            });
        } catch (Throwable ignored) { }
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

        Binding(Object owner, View pager, Method getter) throws NoSuchMethodException {
            this.owner = new WeakReference<Object>(owner);
            this.pager = pager;
            this.getter = getter;
            this.root = pager.getRootView();
            scrollState = pager.getClass().getMethod("getScrollState");
            currentItem = pager.getClass().getMethod("getCurrentItem");
        }

        void attach() {
            NativeUiTrace.event("reels.listener.attached", pager);
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
                if (nextPage != page || nextIndex != index || nextIdle != idle) generation++;
                page = nextPage;
                index = nextIndex;
                idle = nextIdle;
                int id = root.getResources().getIdentifier("clips_viewer_action_bar", "id",
                        root.getContext().getPackageName());
                View anchor = id == 0 ? null : root.findViewById(id);
                NativeUiTrace.event("reels.anchor", anchor);
                if (!(anchor instanceof FrameLayout) || !anchor.isShown()
                        || anchor.getRootView() != pager.getRootView()) {
                    hide();
                    NativeUiTrace.event("reels.reject.anchor", anchor);
                    return true;
                }
                FrameLayout bar = (FrameLayout) anchor;
                if (icon != null && icon.getParent() != bar) removeIcon();
                if (icon == null) {
                    icon = new HmxDownloadIcon(bar.getContext());
                    int size = Math.round(40 * bar.getResources().getDisplayMetrics().density);
                    bar.addView(icon, new FrameLayout.LayoutParams(size, size,
                            Gravity.END | Gravity.CENTER_VERTICAL));
                    icon.setVisibility(View.INVISIBLE);
                    NativeUiTrace.event("reels.created", icon);
                    icon.setOnClickListener(v -> tap());
                }
                boolean available = idle && page != null && DownloaderSwitch.isEnabled(bar.getContext())
                        && freeSlot(bar, icon);
                icon.setVisibility(available ? View.VISIBLE : View.INVISIBLE);
                NativeUiTrace.event(available ? "reels.visible" : !idle ? "reels.reject.not-idle"
                        : page == null ? "reels.reject.no-page"
                        : !DownloaderSwitch.isEnabled(bar.getContext()) ? "reels.reject.off"
                        : "reels.reject.slot", icon);
            } catch (Throwable ignored) { hide(); NativeUiTrace.event("reels.draw.exception", ignored); }
            return true;
        }

        private void tap() {
            try {
                final long captured = generation;
                final Object selected = current();
                final int selectedIndex = (Integer) currentItem.invoke(pager);
                if (!valid(captured, selected, selectedIndex)) return;
                com.feurstagram.extension.downloader.core.ObservedMedia media = MediaModelReader.readWrapped(selected);
                NativeUiTrace.event(media == null ? "reels.tap.unresolved" : "reels.tap.resolved", icon);
                InContextDownloadSheet.showBound(icon.getContext(), media,
                        InContextAction.Surface.REELS, "reels-native-action-bar",
                        () -> valid(captured, selected, selectedIndex));
            } catch (Throwable ignored) { }
        }

        private boolean valid(long captured, Object selected, int selectedIndex) {
            try {
                return selected != null && captured == generation && pager.isAttachedToWindow()
                        && pager.isShown() && icon != null && icon.isShown()
                        && DownloaderSwitch.isEnabled(icon.getContext())
                        && ((Integer) scrollState.invoke(pager)) == 0
                        && ((Integer) currentItem.invoke(pager)) == selectedIndex && current() == selected;
            } catch (Throwable ignored) { return false; }
        }

        private void hide() { if (icon != null) icon.setVisibility(View.INVISIBLE); }
        private void removeIcon() {
            if (icon != null && icon.getParent() instanceof ViewGroup) {
                ((ViewGroup) icon.getParent()).removeView(icon);
            }
            icon = null;
        }
        @Override public void onViewAttachedToWindow(View view) { }
        @Override public void onViewDetachedFromWindow(View view) {
            NativeUiTrace.event("reels.detached", view);
            generation++;
            page = null;
            removeIcon();
            if (root.getViewTreeObserver().isAlive()) root.getViewTreeObserver().removeOnPreDrawListener(this);
            pager.removeOnAttachStateChangeListener(this);
        }
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
                NativeUiTrace.event("reels.slot.blocker", child);
                return false;
            }
        }
        return true;
    }
}
