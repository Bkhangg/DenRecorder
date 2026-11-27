package com.bkggds.denrecorder;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * The filmstrip of the trim screen: small frames of the whole video in a row,
 * the piece to keep lit between two handles, everything else darkened. The
 * handles are dragged directly, like in a video editor, and a tap anywhere on
 * the strip moves the playhead there.
 */
final class TrimBar extends View {

    interface Listener {
        /** The handles moved: this is the piece that will be saved. */
        void onRangeChanged(long startMs, long endMs);

        /** The playhead moved: the frame under it is the one to show. */
        void onScrub(long positionMs);
    }

    private static final int NONE = 0;
    private static final int START = 1;
    private static final int END = 2;
    private static final int SCRUB = 3;

    /** A cut shorter than this is refused, as is one with no room to drag. */
    private static final long MIN_GAP_MS = 1000L;

    private final List<Bitmap> frames = new ArrayList<>();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint imagePaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final RectF shape = new RectF();
    private final Rect source = new Rect();
    private final RectF target = new RectF();

    private long durationMs;
    private long startMs;
    private long endMs;
    private long playheadMs;
    private int mode = NONE;

    private int accent;
    private int grip;
    private int empty;
    private final int handleWidth;
    private final int padding;
    private final float density;

    private Listener listener;

    TrimBar(Context context) {
        super(context);
        density = getResources().getDisplayMetrics().density;
        handleWidth = Math.round(14 * density);
        padding = handleWidth + Math.round(4 * density);
        accent = 0xFFFAFAFA;
        grip = 0xFF111113;
        empty = 0xFF27272A;
        setWillNotDraw(false);
    }

    void setColors(int accent, int grip, int empty) {
        this.accent = accent;
        this.grip = grip;
        this.empty = empty;
        invalidate();
    }

    void setListener(Listener listener) {
        this.listener = listener;
    }

    void setDuration(long durationMs) {
        this.durationMs = Math.max(1L, durationMs);
        if (endMs <= 0 || endMs > this.durationMs) endMs = this.durationMs;
        invalidate();
    }

    void setRange(long startMs, long endMs) {
        this.startMs = clamp(startMs, 0, Math.max(0, durationMs - MIN_GAP_MS));
        this.endMs = clamp(endMs, this.startMs + MIN_GAP_MS, durationMs);
        invalidate();
    }

    long start() {
        return startMs;
    }

    long end() {
        return endMs;
    }

    void setPlayhead(long positionMs) {
        playheadMs = clamp(positionMs, startMs, endMs);
        invalidate();
    }

    /** Frames spread evenly over the video, left to right. */
    void setFrames(List<Bitmap> loaded) {
        frames.clear();
        if (loaded != null) frames.addAll(loaded);
        invalidate();
    }

    // ---------- Drawing ----------

    @Override
    protected void onDraw(Canvas canvas) {
        final float left = padding;
        final float right = getWidth() - padding;
        final float top = 0;
        final float bottom = getHeight();
        if (right <= left) return;

        shape.set(left, top, right, bottom);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(empty);
        canvas.drawRoundRect(shape, 8 * density, 8 * density, paint);

        if (!frames.isEmpty()) {
            canvas.save();
            canvas.clipRect(shape);
            final float cell = (right - left) / frames.size();
            for (int i = 0; i < frames.size(); i++) {
                Bitmap bitmap = frames.get(i);
                if (bitmap == null || bitmap.isRecycled()) continue;
                float cellLeft = left + i * cell;
                drawCover(canvas, bitmap, cellLeft, top, cellLeft + cell, bottom);
            }
            canvas.restore();
        }

        final float startX = xFor(startMs);
        final float endX = xFor(endMs);

        // Everything outside the piece is darkened, so the cut reads at a glance.
        paint.setColor(0xB8000000);
        canvas.save();
        canvas.clipRect(shape);
        canvas.drawRect(left, top, startX, bottom, paint);
        canvas.drawRect(endX, top, right, bottom, paint);
        canvas.restore();

        // The piece itself keeps a bright edge.
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1f, 2 * density));
        paint.setColor(accent);
        shape.set(startX + density, top + density, endX - density, bottom - density);
        canvas.drawRoundRect(shape, 6 * density, 6 * density, paint);
        paint.setStyle(Paint.Style.FILL);

        // The playhead sits under the handles so it never hides them.
        if (playheadMs > startMs && playheadMs < endMs) {
            float headX = xFor(playheadMs);
            paint.setColor(accent);
            canvas.drawRect(headX - density, top + 2 * density,
                    headX + density, bottom - 2 * density, paint);
            canvas.drawCircle(headX, top + 5 * density, 3 * density, paint);
        }

        drawHandle(canvas, startX, top, bottom);
        drawHandle(canvas, endX, top, bottom);
    }

    /** One frame, scaled up to fill its cell and cropped instead of squashed. */
    private void drawCover(Canvas canvas, Bitmap bitmap, float left, float top,
                           float right, float bottom) {
        float boxWidth = right - left;
        float boxHeight = bottom - top;
        if (boxWidth <= 0 || boxHeight <= 0) return;
        float scale = Math.max(boxWidth / bitmap.getWidth(), boxHeight / bitmap.getHeight());
        float drawnWidth = bitmap.getWidth() * scale;
        float drawnHeight = bitmap.getHeight() * scale;
        float cropX = (drawnWidth - boxWidth) / 2f / scale;
        float cropY = (drawnHeight - boxHeight) / 2f / scale;
        source.set((int) cropX, (int) cropY,
                Math.min(bitmap.getWidth(), (int) (cropX + boxWidth / scale)),
                Math.min(bitmap.getHeight(), (int) (cropY + boxHeight / scale)));
        if (source.width() <= 0 || source.height() <= 0) return;
        target.set(left, top, right, bottom);
        canvas.drawBitmap(bitmap, source, target, imagePaint);
    }

    /** A bar with a grip in the middle: the part the finger takes hold of. */
    private void drawHandle(Canvas canvas, float x, float top, float bottom) {
        float half = handleWidth / 2f;
        shape.set(x - half, top, x + half, bottom);
        paint.setColor(accent);
        canvas.drawRoundRect(shape, 5 * density, 5 * density, paint);

        paint.setColor(grip);
        float gripHeight = Math.min(bottom - top, 26 * density);
        float center = (top + bottom) / 2f;
        shape.set(x - density, center - gripHeight / 2f, x + density, center + gripHeight / 2f);
        canvas.drawRoundRect(shape, density, density, paint);
    }

    // ---------- Touch ----------

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        float x = event.getX();
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                mode = pick(x);
                apply(x);
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (mode != NONE) apply(x);
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                if (mode != NONE) apply(x);
                mode = NONE;
                invalidate();
                return true;
            }
            default:
                return super.onTouchEvent(event);
        }
    }

    /** The nearest handle wins; anywhere else on the strip scrubs. */
    private int pick(float x) {
        float startX = xFor(startMs);
        float endX = xFor(endMs);
        float grab = handleWidth * 1.7f;
        float toStart = Math.abs(x - startX);
        float toEnd = Math.abs(x - endX);
        if (toStart <= grab && toStart <= toEnd) return START;
        if (toEnd <= grab) return END;
        return SCRUB;
    }

    private void apply(float x) {
        long ms = msFor(x);
        if (mode == START) {
            startMs = clamp(ms, 0, endMs - MIN_GAP_MS);
            playheadMs = startMs;
            notifyRange();
            notifyScrub();
        } else if (mode == END) {
            endMs = clamp(ms, startMs + MIN_GAP_MS, durationMs);
            playheadMs = endMs;
            notifyRange();
            notifyScrub();
        } else if (mode == SCRUB) {
            playheadMs = clamp(ms, startMs, endMs);
            notifyScrub();
        }
        invalidate();
    }

    private void notifyRange() {
        if (listener != null) listener.onRangeChanged(startMs, endMs);
    }

    private void notifyScrub() {
        if (listener != null) listener.onScrub(playheadMs);
    }

    // ---------- Coordinates ----------

    private float xFor(long ms) {
        float left = padding;
        float width = getWidth() - padding * 2;
        return left + width * clamp(ms, 0, durationMs) / (float) durationMs;
    }

    private long msFor(float x) {
        float left = padding;
        float width = Math.max(1f, getWidth() - padding * 2);
        float ratio = (x - left) / width;
        return (long) (clamp(ratio, 0f, 1f) * durationMs);
    }

    private static long clamp(long value, long min, long max) {
        return value < min ? min : (value > max ? max : value);
    }

    private static float clamp(float value, float min, float max) {
        return value < min ? min : (value > max ? max : value);
    }
}
