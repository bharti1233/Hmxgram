package com.feurstagram.extension.downloader.android;

import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import java.util.LinkedHashMap;
import java.util.Map;

/** Bounded structural diagnostics. Never records text, model values or URLs. */
public final class NativeUiTrace {
    private static final Map<String, Integer> COUNTS = new LinkedHashMap<String, Integer>();
    private NativeUiTrace() { }

    public static synchronized void event(String stage, Object object) {
        try {
            int count = COUNTS.containsKey(stage) ? COUNTS.get(stage) + 1 : 1;
            if (COUNTS.size() >= 128 && !COUNTS.containsKey(stage)) return;
            COUNTS.put(stage, count);
            if (count > 3 && (count & (count - 1)) != 0) return;
            String detail = object == null ? "null" : object.getClass().getName();
            if (object instanceof View) {
                View view = (View) object;
                String id = "none";
                try { id = view.getResources().getResourceEntryName(view.getId()); }
                catch (RuntimeException ignored) { }
                detail += " id=" + id + " attached=" + view.isAttachedToWindow()
                        + " shown=" + view.isShown() + " visibility=" + view.getVisibility()
                        + " size=" + view.getWidth() + "x" + view.getHeight()
                        + " measured=" + view.getMeasuredWidth() + "x" + view.getMeasuredHeight()
                        + " alpha=" + view.getAlpha()
                        + " enabled=" + DownloaderSwitch.isEnabled(view.getContext())
                        + " parent=" + (view.getParent() == null ? "none"
                                : view.getParent().getClass().getName());
                if (view.getLayoutParams() != null) detail += " lp="
                        + view.getLayoutParams().getClass().getName() + ":"
                        + view.getLayoutParams().width + "x" + view.getLayoutParams().height;
            }
            Log.i("HmxNativeUI", "m11.1 stage=" + stage + " count=" + count + " " + detail);
        } catch (Throwable ignored) { }
    }

    public static void children(String stage, ViewGroup parent) {
        event(stage, parent);
        for (int i = 0; i < Math.min(16, parent.getChildCount()); i++) {
            event(stage + ".child" + i, parent.getChildAt(i));
        }
    }
}
