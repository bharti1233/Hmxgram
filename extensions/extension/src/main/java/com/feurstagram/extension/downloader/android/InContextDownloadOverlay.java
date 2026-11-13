package com.feurstagram.extension.downloader.android;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.feurstagram.extension.Settings;
import com.feurstagram.extension.downloader.core.InContextAction;
import com.feurstagram.extension.downloader.core.ObservedMedia;

/**
 * The HMX-owned download action drawn over Instagram's window.
 *
 * <p>This is deliberately not a row of Instagram's list and not a view inside
 * a post. The protected row binder is never modified, no Instagram view is
 * touched and nothing is parsed from the screen. The chip reads the latest
 * observation the downloader's existing read-only observer already made, and
 * only while that observation is fresh; tapping it opens a confirmation
 * sheet. The sheet names the media and asks before anything is queued.
 *
 * <p>The chip is placed in the window's own {@link FrameLayout}, not in a
 * system overlay: it needs no permission, it lives exactly as long as the
 * Instagram window, and it never changes Instagram's layout. It is draggable
 * and remembers where the user put it.
 *
 * <p>It is only offered on a supported surface: a feed row's action button,
 * the reels action bar or the story viewer's reply bar must be present in the
 * view tree. Those are read-only lookups by resource name, the same technique
 * the extension already uses for its hiders.
 *
 * <p>This class is installed by the "In-context download actions" patch. It
 * is absent from builds that do not enable that patch.
 */
public final class InContextDownloadOverlay {

    private static final String PREFS = "feurstagram_incontext";
    private static final String KEY_X = "chip_x";
    private static final String KEY_Y = "chip_y";

    /** Tag that makes the install idempotent for a window. */
    private static final Object VIEW_TAG = new Object();

    /** Every 250 ms until the tab bar is attached to its window. */
    private static final int ATTACH_ATTEMPTS = 20;

    /** How often the chip re-checks the observation's freshness. */
    private static final long REFRESH_MS = 1000L;

    private static final String[] FEED_ANCHORS = {"like_button"};
    private static final String[] REELS_ANCHORS = {
            "clips_ufi_like_button", "clips_viewer_action_bar"};
    private static final String[] STORY_ANCHORS = {
            "reel_viewer_message_composer", "reel_viewer_header"};

    private InContextDownloadOverlay() {
    }

    /**
     * Installs the chip once the tab bar is attached to Instagram's window.
     *
     * <p>Called by the patch from the HMX tab-bar watcher, so a stock build
     * (without the M7 patch) never reaches this class.
     */
    public static void install(final ViewGroup tabBar) {
        if (tabBar == null) {
            return;
        }
        tabBar.post(new Runnable() {
            @Override
            public void run() {
                attach(tabBar, 0);
            }
        });
    }

    private static void attach(final ViewGroup tabBar, final int attempt) {
        if (!tabBar.isAttachedToWindow()) {
            retry(tabBar, attempt);
            return;
        }
        View rootView = tabBar.getRootView();
        if (!(rootView instanceof FrameLayout) || rootView == tabBar) {
            // The window root is a FrameLayout by contract. Until it is there,
            // wait: adding the chip to the tab bar itself would move it with
            // the bar and could disturb its layout.
            retry(tabBar, attempt);
            return;
        }
        final FrameLayout root = (FrameLayout) rootView;
        if (root.findViewWithTag(VIEW_TAG) != null) {
            return;
        }
        final Chip chip = new Chip(root.getContext());
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.leftMargin = chip.savedX();
        params.topMargin = chip.savedY();
        root.addView(chip, params);
        chip.begin();
    }

    private static void retry(final ViewGroup tabBar, final int attempt) {
        if (attempt >= ATTACH_ATTEMPTS) {
            return;
        }
        tabBar.postDelayed(new Runnable() {
            @Override
            public void run() {
                attach(tabBar, attempt + 1);
            }
        }, 250L);
    }

    /** The chip itself. One per Instagram window. */
    private static final class Chip extends TextView
            implements MediaObserver.Listener, View.OnTouchListener,
            View.OnAttachStateChangeListener {

        private final Handler handler = new Handler(Looper.getMainLooper());
        private final int slop;
        private final SharedPreferences prefs;

        private boolean dragging;
        private float downRawX;
        private float downRawY;

        private final Runnable tick = new Runnable() {
            @Override
            public void run() {
                if (getWindowToken() == null) {
                    return;
                }
                refresh();
                handler.postDelayed(this, REFRESH_MS);
            }
        };

        Chip(Context context) {
            super(context);
            prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            slop = ViewConfiguration.get(context).getScaledTouchSlop();

            setText("HMX save");
            setTextSize(12f);
            setTextColor(0xFFFFFFFF);
            setGravity(Gravity.CENTER);
            int padH = dp(12);
            int padV = dp(8);
            setPadding(padH, padV, padH, padV);

            GradientDrawable background = new GradientDrawable();
            background.setColor(0xE6111111);
            background.setCornerRadius(dp(200));
            background.setStroke(dp(1), 0x66FFFFFF);
            setBackground(background);
            setElevation(dp(6));
            setAlpha(0.94f);

            setClickable(true);
            setFocusable(true);
            setContentDescription("HMX Insta: download the media on screen");
            setTag(VIEW_TAG);
            setOnTouchListener(this);
            setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View view) {
                    openSheet();
                }
            });
            setVisibility(GONE);
        }

        void begin() {
            addOnAttachStateChangeListener(this);
            MediaObserver.addListener(this);
            post(new Runnable() {
                @Override
                public void run() {
                    clampIntoParent();
                }
            });
            refresh();
            handler.postDelayed(tick, REFRESH_MS);
        }

        /** Re-evaluates whether the action may be offered right now. */
        private void refresh() {
            InContextAction.Surface surface = surface();
            ObservedMedia media = MediaObserver.latest();
            long ageMs = MediaObserver.latestAgeMs();
            setVisibility(InContextAction.canOffer(media, ageMs, surface) ? VISIBLE : GONE);
        }

        private void openSheet() {
            ObservedMedia media = MediaObserver.latest();
            long ageMs = MediaObserver.latestAgeMs();
            InContextAction.Surface surface = surface();
            if (!InContextAction.canOffer(media, ageMs, surface)) {
                refresh();
                return;
            }
            Context activity = Settings.getActivityContext(this);
            if (activity == null) {
                activity = getContext();
            }
            InContextDownloadSheet.show(activity, media, ageMs, surface);
        }

        // --- Surface detection -------------------------------------------------

        private InContextAction.Surface surface() {
            if (hasAny(FEED_ANCHORS)) {
                return InContextAction.Surface.FEED;
            }
            if (hasAny(REELS_ANCHORS)) {
                return InContextAction.Surface.REELS;
            }
            if (hasAny(STORY_ANCHORS)) {
                return InContextAction.Surface.STORIES;
            }
            return null;
        }

        private boolean hasAny(String[] resourceNames) {
            View root = getRootView();
            if (root == null) {
                return false;
            }
            for (String name : resourceNames) {
                int id = resolveId(name);
                if (id != 0 && root.findViewById(id) != null) {
                    return true;
                }
            }
            return false;
        }

        private int resolveId(String name) {
            Resources resources = getResources();
            // Ask the resource table which package it is actually under. In a
            // clone build that is still the original resource package, while
            // getPackageName() is the new one. No package name is hardcoded.
            String resourcePackage = getContext().getPackageName();
            try {
                int icon = getContext().getApplicationInfo().icon;
                if (icon != 0) {
                    resourcePackage = resources.getResourcePackageName(icon);
                }
            } catch (RuntimeException ignored) {
                // Fall back to the app package.
            }
            int id = resources.getIdentifier(name, "id", resourcePackage);
            if (id == 0) {
                id = resources.getIdentifier(name, "id", getContext().getPackageName());
            }
            return id;
        }

        // --- Drag and position -------------------------------------------------

        @Override
        public boolean onTouch(View view, MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    dragging = false;
                    downRawX = event.getRawX();
                    downRawY = event.getRawY();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = event.getRawX() - downRawX;
                    float dy = event.getRawY() - downRawY;
                    if (!dragging && Math.hypot(dx, dy) > slop) {
                        dragging = true;
                    }
                    if (dragging) {
                        setTranslationX(clampTranslationX(dx));
                        setTranslationY(clampTranslationY(dy));
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    if (dragging) {
                        persistPosition();
                        dragging = false;
                    } else {
                        performClick();
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    if (dragging) {
                        persistPosition();
                        dragging = false;
                    }
                    return true;
                default:
                    return false;
            }
        }

        private float clampTranslationX(float dx) {
            View parent = (View) getParent();
            if (parent == null) {
                return 0f;
            }
            float min = -getLeft();
            float max = parent.getWidth() - getWidth() - getLeft();
            return Math.max(min, Math.min(max, dx));
        }

        private float clampTranslationY(float dy) {
            View parent = (View) getParent();
            if (parent == null) {
                return 0f;
            }
            float min = -getTop();
            float max = parent.getHeight() - getHeight() - getTop();
            return Math.max(min, Math.min(max, dy));
        }

        private void persistPosition() {
            int x = Math.round(getLeft() + getTranslationX());
            int y = Math.round(getTop() + getTranslationY());
            setTranslationX(0f);
            setTranslationY(0f);
            moveTo(x, y);
            prefs.edit().putInt(KEY_X, Math.max(0, x)).putInt(KEY_Y, Math.max(0, y)).apply();
        }

        private void clampIntoParent() {
            View parent = (View) getParent();
            if (parent == null || parent.getWidth() == 0) {
                return;
            }
            int x = Math.max(0, Math.min(getLeft(), parent.getWidth() - getWidth()));
            int y = Math.max(0, Math.min(getTop(), parent.getHeight() - getHeight()));
            if (x != getLeft() || y != getTop()) {
                moveTo(x, y);
            }
            if (prefs.getInt(KEY_X, -1) != x || prefs.getInt(KEY_Y, -1) != y) {
                prefs.edit().putInt(KEY_X, x).putInt(KEY_Y, y).apply();
            }
        }

        private void moveTo(int x, int y) {
            ViewGroup.LayoutParams current = getLayoutParams();
            if (!(current instanceof FrameLayout.LayoutParams)) {
                return;
            }
            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) current;
            params.gravity = Gravity.TOP | Gravity.START;
            params.leftMargin = x;
            params.topMargin = y;
            setLayoutParams(params);
        }

        int savedX() {
            return Math.max(dp(8), prefs.getInt(KEY_X, dp(8)));
        }

        int savedY() {
            return Math.max(dp(96), prefs.getInt(KEY_Y, dp(96)));
        }

        private int dp(float value) {
            return Math.round(value * getResources().getDisplayMetrics().density);
        }

        // --- Lifecycle ---------------------------------------------------------

        @Override
        public void onMediaObserved(ObservedMedia media) {
            refresh();
        }

        @Override
        public void onViewAttachedToWindow(View view) {
            refresh();
            handler.removeCallbacks(tick);
            handler.postDelayed(tick, REFRESH_MS);
        }

        @Override
        public void onViewDetachedFromWindow(View view) {
            handler.removeCallbacks(tick);
            MediaObserver.removeListener(this);
            removeOnAttachStateChangeListener(this);
        }
    }
}
