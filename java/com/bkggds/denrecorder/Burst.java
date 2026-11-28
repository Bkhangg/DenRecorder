package com.bkggds.denrecorder;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.provider.Settings;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/**
 * A manga impact: speed lines thrown out of a point with a word landing on
 * top. Used when recording starts or stops, so the press is felt as well as
 * seen. Drawn in code, nothing to load, and skipped when the system has
 * animations turned off.
 */
public class Burst extends View {

    private static final String[] WORDS = {"ドン!", "カチッ", "ズザッ"};

    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint wordPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path spike = new Path();

    private float progress = 1f;
    private float centerX;
    private float centerY;
    private String word = WORDS[0];
    private int ink;
    private int soft;
    private int accent;

    private ValueAnimator animator;

    public Burst(Context context) {
        super(context);
        setWillNotDraw(false);
        linePaint.setStyle(Paint.Style.FILL);
        wordPaint.setTextAlign(Paint.Align.CENTER);
        wordPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD_ITALIC));
    }

    /** Sets the palette once the app's theme is known. */
    public void setColors(int ink, int soft, int accent) {
        this.ink = ink;
        this.soft = soft;
        this.accent = accent;
    }

    /**
     * Fires at a point in this view's own coordinates: the middle of the
     * control that was pressed. The word picks itself.
     */
    public void play(float x, float y) {
        if (!animationsOn()) return;
        centerX = x;
        centerY = y;
        word = WORDS[(int) (Math.random() * WORDS.length)];
        if (animator != null) animator.cancel();
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(560);
        animator.setInterpolator(new DecelerateInterpolator(1.6f));
        animator.addUpdateListener(animation -> {
            progress = (float) animation.getAnimatedValue();
            invalidate();
        });
        animator.start();
    }

    private boolean animationsOn() {
        try {
            return Settings.Global.getFloat(getContext().getContentResolver(),
                    Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f;
        } catch (Exception e) {
            return true;
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (progress >= 1f) return;

        float p = progress;
        float out = (float) Math.sin(Math.PI * Math.min(1f, p * 1.15f));
        float reach = dp(46) + dp(96) * p;
        int alpha = (int) (255 * (1f - p * 0.9f));

        for (int i = 0; i < 16; i++) {
            double angle = Math.PI * 2 * i / 16 + (i % 2 == 0 ? 0.05 : -0.09);
            float length = reach * (i % 3 == 0 ? 1f : 0.66f);
            float half = dp(7) * (1f - p * 0.7f);
            float cos = (float) Math.cos(angle);
            float sin = (float) Math.sin(angle);
            // A long thin triangle from just outside the middle to the tip.
            spike.reset();
            spike.moveTo(centerX + cos * dp(18) - sin * half,
                    centerY + sin * dp(18) + cos * half);
            spike.lineTo(centerX + cos * length, centerY + sin * length);
            spike.lineTo(centerX + cos * dp(18) + sin * half,
                    centerY + sin * dp(18) - cos * half);
            spike.close();
            linePaint.setColor(i % 4 == 0 ? accent : (i % 2 == 0 ? ink : soft));
            linePaint.setAlpha(alpha);
            canvas.drawPath(spike, linePaint);
        }

        if (out <= 0.02f) return;
        // The word overshoots and settles, then leaves with the lines.
        float scale = 0.55f + 0.6f * Math.min(1f, p * 2.4f);
        wordPaint.setTextSize(dp(26) * scale);
        wordPaint.setStyle(Paint.Style.STROKE);
        wordPaint.setStrokeWidth(dp(5));
        wordPaint.setColor(0xFFFFFFFF);
        wordPaint.setAlpha(alpha);
        canvas.drawText(word, centerX, centerY + dp(9) * scale, wordPaint);
        wordPaint.setStyle(Paint.Style.FILL);
        wordPaint.setColor(ink);
        wordPaint.setAlpha(alpha);
        canvas.drawText(word, centerX, centerY + dp(9) * scale, wordPaint);
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density + 0.5f;
    }
}
