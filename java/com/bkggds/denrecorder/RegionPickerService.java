package com.bkggds.denrecorder;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Chooses the part of the screen to record.
 *
 * It is a floating frame rather than a screen of its own: the user opens it,
 * goes to the app or game they want, and drags the frame over what should end
 * up in the video. "Save" stores the frame as parts of the screen, so it still
 * fits when the recording is measured in another rotation.
 */
public class RegionPickerService extends Service {

    private static final String ACTION_START = "com.bkggds.denrecorder.region.START";
    private static final String ACTION_STOP = "com.bkggds.denrecorder.region.STOP";

    private static final int PANEL = Color.parseColor("#EE111113");
    private static final int BORDER = Color.parseColor("#F43F5E");
    private static final int TEXT = Color.parseColor("#FAFAFA");
    private static final int DIM = 0xAA000000;

    private static volatile boolean running;

    private WindowManager windowManager;
    private View overlay;
    private RegionView regionView;
    private int screenWidth;
    private int screenHeight;

    public static boolean canDraw(Context context) {
        return DrawOverlayService.canDraw(context);
    }

    public static boolean isRunning() {
        return running;
    }

    public static void start(Context context) {
        if (!canDraw(context)) return;
        try {
            context.startService(new Intent(context, RegionPickerService.class)
                    .setAction(ACTION_START));
        } catch (Exception ignored) { }
    }

    public static void stop(Context context) {
        try {
            context.startService(new Intent(context, RegionPickerService.class)
                    .setAction(ACTION_STOP));
        } catch (Exception ignored) { }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (overlay == null) show();
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        running = false;
        if (overlay != null && windowManager != null) {
            try { windowManager.removeView(overlay); } catch (Exception ignored) { }
        }
        overlay = null;
        regionView = null;
        super.onDestroy();
    }

    private void show() {
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        DisplayMetrics dm = new DisplayMetrics();
        try {
            windowManager.getDefaultDisplay().getRealMetrics(dm);
        } catch (Exception ignored) { }
        screenWidth = Math.max(1, dm.widthPixels);
        screenHeight = Math.max(1, dm.heightPixels);

        FrameLayout root = new FrameLayout(this);
        regionView = new RegionView(this);
        root.addView(regionView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setPadding(dp(6), dp(6), dp(6), dp(6));
        bar.setBackground(round(PANEL, 20));

        TextView cancel = chip(getString(R.string.dialog_cancel), TEXT);
        cancel.setOnClickListener(v -> stopSelf());
        bar.addView(cancel, new LinearLayout.LayoutParams(dp(96), dp(44)));

        TextView save = chip(getString(R.string.region_save), BORDER);
        save.setOnClickListener(v -> save());
        LinearLayout.LayoutParams saveLp = new LinearLayout.LayoutParams(dp(110), dp(44));
        saveLp.leftMargin = dp(6);
        bar.addView(save, saveLp);

        FrameLayout.LayoutParams barLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        barLp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        barLp.bottomMargin = dp(40);
        root.addView(bar, barLp);

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                Build.VERSION.SDK_INT >= 26
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;

        try {
            windowManager.addView(root, params);
            overlay = root;
            running = true;
        } catch (Exception e) {
            running = false;
            Toast.makeText(this, R.string.region_failed, Toast.LENGTH_SHORT).show();
            stopSelf();
        }
    }

    private void save() {
        if (regionView == null) return;
        Rect rect = regionView.getRect();
        float width = (float) rect.width() / screenWidth;
        float height = (float) rect.height() / screenHeight;
        getSharedPreferences("settings", MODE_PRIVATE).edit()
                .putBoolean("regionOn", true)
                .putFloat("regionX", (float) rect.left / screenWidth)
                .putFloat("regionY", (float) rect.top / screenHeight)
                .putFloat("regionW", width)
                .putFloat("regionH", height)
                .apply();
        Toast.makeText(this,
                getString(R.string.region_saved, rect.width(), rect.height()),
                Toast.LENGTH_SHORT).show();
        stopSelf();
    }

    private TextView chip(String text, int color) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setTextColor(color);
        view.setGravity(Gravity.CENTER);
        view.setBackground(round(Color.parseColor("#1F1F23"), 14));
        return view;
    }

    private GradientDrawable round(int color, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(radius));
        shape.setStroke(dp(1), Color.parseColor("#3F3F46"));
        return shape;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    /**
     * The frame itself: a dark veil around the chosen part of the screen, a red
     * border and four corners to pull. Dragging inside moves it, dragging
     * outside draws a new one.
     */
    private final class RegionView extends View {

        private static final int NONE = 0;
        private static final int MOVE = 1;
        private static final int NEW = 8;

        private final Rect rect = new Rect();
        private final Paint veil = new Paint();
        private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint handle = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint badge = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint hint = new Paint(Paint.ANTI_ALIAS_FLAG);

        private int mode = NONE;
        private int corner;   // 0 top-left, 1 top-right, 2 bottom-left, 3 bottom-right
        private float downX;
        private float downY;
        private Rect startRect = new Rect();

        RegionView(Context context) {
            super(context);
            veil.setColor(DIM);
            border.setStyle(Paint.Style.STROKE);
            border.setStrokeWidth(dp(2));
            border.setColor(BORDER);
            handle.setColor(Color.WHITE);
            badge.setColor(PANEL);
            label.setColor(TEXT);
            label.setTextSize(dp(13));
            label.setTypeface(Typeface.DEFAULT_BOLD);
            hint.setColor(0xCCFFFFFF);
            hint.setTextSize(dp(13));
            hint.setTextAlign(Paint.Align.CENTER);

            // Start from the area already saved, or a wide band in the middle.
            android.content.SharedPreferences prefs =
                    getSharedPreferences("settings", MODE_PRIVATE);
            if (prefs.getBoolean("regionOn", false)) {
                int left = Math.round(prefs.getFloat("regionX", 0.1f) * screenWidth);
                int top = Math.round(prefs.getFloat("regionY", 0.3f) * screenHeight);
                int w = Math.round(prefs.getFloat("regionW", 0.8f) * screenWidth);
                int h = Math.round(prefs.getFloat("regionH", 0.4f) * screenHeight);
                rect.set(left, top, left + w, top + h);
            } else {
                int w = Math.round(screenWidth * 0.82f);
                int h = Math.round(screenHeight * 0.4f);
                rect.set((screenWidth - w) / 2, (screenHeight - h) / 2,
                        (screenWidth + w) / 2, (screenHeight + h) / 2);
            }
            clamp();
        }

        Rect getRect() {
            return new Rect(rect);
        }

        private int handleRadius() {
            return dp(22);
        }

        private void clamp() {
            int min = dp(80);
            if (rect.width() < min) rect.right = rect.left + min;
            if (rect.height() < min) rect.bottom = rect.top + min;
            if (rect.left < 0) rect.offset(-rect.left, 0);
            if (rect.top < 0) rect.offset(0, -rect.top);
            if (rect.right > screenWidth) rect.offset(screenWidth - rect.right, 0);
            if (rect.bottom > screenHeight) rect.offset(0, screenHeight - rect.bottom);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);

            // The veil: four bands around the frame, so the chosen part of the
            // screen shows through untouched.
            canvas.drawRect(0, 0, getWidth(), rect.top, veil);
            canvas.drawRect(0, rect.bottom, getWidth(), getHeight(), veil);
            canvas.drawRect(0, rect.top, rect.left, rect.bottom, veil);
            canvas.drawRect(rect.right, rect.top, getWidth(), rect.bottom, veil);

            canvas.drawRect(rect, border);
            int r = handleRadius() / 2;
            int[][] corners = {
                    {rect.left, rect.top}, {rect.right, rect.top},
                    {rect.left, rect.bottom}, {rect.right, rect.bottom}
            };
            for (int[] point : corners) {
                canvas.drawCircle(point[0], point[1], r, handle);
                canvas.drawCircle(point[0], point[1], r + dp(2), border);
            }

            // The size of the region, in the corner of the frame.
            String size = rect.width() + " × " + rect.height();
            float textWidth = label.measureText(size);
            RectF tag = new RectF(rect.left + dp(10), rect.top + dp(10),
                    rect.left + dp(26) + textWidth, rect.top + dp(40));
            canvas.drawRoundRect(tag, dp(8), dp(8), badge);
            canvas.drawText(size, tag.left + dp(8), tag.bottom - dp(9), label);

            canvas.drawText(getString(R.string.region_hint),
                    getWidth() / 2f, dp(60) + dp(24), hint);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            float x = event.getX();
            float y = event.getY();
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = x;
                    downY = y;
                    startRect.set(rect);
                    corner = cornerAt(x, y);
                    if (corner >= 0) {
                        mode = corner + 2;   // 2…5: the four corners
                    } else if (rect.contains((int) x, (int) y)) {
                        mode = MOVE;
                    } else {
                        mode = NEW;
                        rect.set((int) x, (int) y, (int) x, (int) y);
                    }
                    invalidate();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (mode == MOVE) {
                        rect.set(startRect);
                        rect.offset((int) (x - downX), (int) (y - downY));
                        clamp();
                        invalidate();
                    } else if (mode == NEW) {
                        rect.set(Math.min((int) downX, (int) x), Math.min((int) downY, (int) y),
                                Math.max((int) downX, (int) x), Math.max((int) downY, (int) y));
                        invalidate();
                    } else if (mode > 1) {
                        rect.set(startRect);
                        int dx = (int) (x - downX);
                        int dy = (int) (y - downY);
                        switch (mode) {
                            case 2: rect.left += dx; rect.top += dy; break;
                            case 3: rect.right += dx; rect.top += dy; break;
                            case 4: rect.left += dx; rect.bottom += dy; break;
                            default: rect.right += dx; rect.bottom += dy; break;
                        }
                        if (rect.left < 0) rect.left = 0;
                        if (rect.top < 0) rect.top = 0;
                        if (rect.right > screenWidth) rect.right = screenWidth;
                        if (rect.bottom > screenHeight) rect.bottom = screenHeight;
                        invalidate();
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    clamp();
                    mode = NONE;
                    invalidate();
                    return true;
                default:
                    return super.onTouchEvent(event);
            }
        }

        /** Which corner the finger is on, or -1. */
        private int cornerAt(float x, float y) {
            int touch = dp(44);
            if (Math.abs(x - rect.left) < touch && Math.abs(y - rect.top) < touch) return 0;
            if (Math.abs(x - rect.right) < touch && Math.abs(y - rect.top) < touch) return 1;
            if (Math.abs(x - rect.left) < touch && Math.abs(y - rect.bottom) < touch) return 2;
            if (Math.abs(x - rect.right) < touch && Math.abs(y - rect.bottom) < touch) return 3;
            return -1;
        }
    }
}
