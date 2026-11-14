package com.feurstagram.extension.downloader.android;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.drawable.GradientDrawable;
import android.view.View;

import com.feurstagram.extension.downloader.core.InContextAction;
import com.feurstagram.extension.downloader.core.MediaModelReader;
import com.feurstagram.extension.downloader.core.ObservedMedia;

/**
 * The icon-only HMX download action.
 *
 * <p>It is our own view, added next to Instagram's native action, and it is
 * deliberately not a text chip: the user asked for a download icon that looks
 * at home in Instagram's dark interface. The glyph is drawn by this class, so
 * it does not depend on a font, a resource id or a theme attribute that a
 * future Instagram release could rename.
 *
 * <p>Each icon carries the <em>exact</em> media bound to its row or Reel page.
 * There is no shared "current media" field: a tap can only offer the media
 * that was read from the control the user actually touched.
 */
public final class HmxDownloadIcon extends View {

    private static final int BACKGROUND = 0xCC000000;
    private static final int STROKE = 0x33FFFFFF;
    private static final int GLYPH = 0xFFF4F4F4;
    private static final int GLYPH_DISABLED = 0x66F4F4F4;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path arrow = new Path();

    private Object boundModel;
    private ObservedMedia resolved;
    private InContextAction.Surface boundSurface;
    private String boundSource;
    private boolean active = true;
    private final com.feurstagram.extension.downloader.core.BindingGeneration bindingGeneration =
            new com.feurstagram.extension.downloader.core.BindingGeneration();

    public long bindingGeneration() { return bindingGeneration.current(); }

    public HmxDownloadIcon(Context context) {
        super(context);
        setClickable(true);
        setFocusable(true);
        setContentDescription("Download with HMX");
        GradientDrawable background = new GradientDrawable();
        background.setShape(GradientDrawable.OVAL);
        background.setColor(BACKGROUND);
        background.setStroke(dp(1), STROKE);
        setBackground(background);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setStrokeWidth(dp(1.8f));
    }

    /**
     * Binds this icon to the model owned by exactly one row.
     *
     * <p>Only the reference is captured here; the model is read when the user
     * taps, so a long feed does not pay for reflection during its scroll.
     */
    public void bindModel(Object model, InContextAction.Surface surface, String source) {
        bindingGeneration.invalidate();
        this.boundModel = model;
        this.resolved = null;
        this.boundSurface = surface;
        this.boundSource = source;
        setActive(model != null);
    }

    /** Binds the icon to an already resolved page media (Reels). */
    void bindMedia(ObservedMedia media, InContextAction.Surface surface, String source) {
        this.boundModel = null;
        this.resolved = media;
        this.boundSurface = surface;
        this.boundSource = source;
        setActive(media != null && media.kind() != null);
    }

    /**
     * The media behind this control.
     *
     * <p>A feed icon reads the model captured at bind time. A Reels icon is
     * resolved again by the caller at tap time from the live page, so this
     * returns the snapshot only when the caller put one there.
     */
    public ObservedMedia media() {
        return boundModel == null ? resolved : MediaModelReader.readWrapped(boundModel);
    }

    @Override
    protected void onDetachedFromWindow() {
        bindingGeneration.invalidate();
        boundModel = null;
        resolved = null;
        super.onDetachedFromWindow();
    }

    InContextAction.Surface surface() {
        return boundSurface;
    }

    String source() {
        return boundSource;
    }

    /** Whether the media behind this control can be offered. */
    void setActive(boolean value) {
        if (active == value) {
            return;
        }
        active = value;
        setAlpha(value ? 1f : 0.35f);
        setEnabled(value);
        invalidate();
    }

    boolean isActive() {
        return active;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int size = dp(40);
        setMeasuredDimension(
                resolveSize(size, widthMeasureSpec),
                resolveSize(size, heightMeasureSpec));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float width = getWidth();
        float height = getHeight();
        if (width <= 0f || height <= 0f) {
            return;
        }
        float centerX = width / 2f;
        float top = height * 0.30f;
        float shaftBottom = height * 0.54f;
        float head = width * 0.16f;
        float trayY = height * 0.68f;
        float trayFrom = width * 0.30f;
        float trayTo = width * 0.70f;

        paint.setColor(active ? GLYPH : GLYPH_DISABLED);
        arrow.reset();
        arrow.moveTo(centerX, top);
        arrow.lineTo(centerX, shaftBottom);
        arrow.moveTo(centerX - head, shaftBottom - head);
        arrow.lineTo(centerX, shaftBottom);
        arrow.lineTo(centerX + head, shaftBottom - head);
        arrow.moveTo(trayFrom, trayY);
        arrow.lineTo(trayFrom, trayY + dp(1));
        arrow.moveTo(trayFrom, trayY);
        arrow.lineTo(trayTo, trayY);
        canvas.drawPath(arrow, paint);
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    /** A pressed tint that does not depend on the host theme. */
    @Override
    public void setPressed(boolean pressed) {
        super.setPressed(pressed);
        setAlpha(pressed ? 0.6f : (active ? 1f : 0.35f));
    }

    @Override
    public CharSequence getAccessibilityClassName() {
        return HmxDownloadIcon.class.getName();
    }

    /** Keeps the background circular when the view is reused by a row. */
    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        GradientDrawable background = new GradientDrawable();
        background.setShape(GradientDrawable.OVAL);
        background.setColor(BACKGROUND);
        background.setStroke(dp(1), STROKE);
        setBackground(background);
    }
}
