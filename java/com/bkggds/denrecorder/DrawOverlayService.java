package com.bkggds.denrecorder;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;

import java.util.ArrayList;
import java.util.List;

/**
 * The little floating button that shows while recording: it draws a rectangle
 * or a circle straight onto the screen (so they end up in the video) and holds
 * pause and stop for the recording.
 *
 * It lives in the recorder's process, so the recording's foreground service
 * keeps it alive; drawing is only possible after the "display over other apps"
 * permission was given.
 */
public class DrawOverlayService extends Service {

    public static final String ACTION_START = "com.bkggds.denrecorder.overlay.START";
    public static final String ACTION_STOP = "com.bkggds.denrecorder.overlay.STOP";

    private static final int INK = Color.parseColor("#F43F5E");
    private static final int PANEL = Color.parseColor("#EE111113");
    private static final int BORDER = Color.parseColor("#3F3F46");
    private static final int TEXT = Color.parseColor("#FAFAFA");
    private static final int ACTIVE = Color.parseColor("#F43F5E");

    private static volatile DrawOverlayService instance;

    public static boolean canDraw(Context context) {
        if (Build.VERSION.SDK_INT < 23) return true;
        try {
            return Settings.canDrawOverlays(context);
        } catch (Exception e) {
            return false;
        }
    }

    public static void start(Context context) {
        if (!canDraw(context)) return;
        try {
            context.startService(new Intent(context, DrawOverlayService.class)
                    .setAction(ACTION_START));
        } catch (Exception ignored) { }
    }

    public static void stop(Context context) {
        try {
            context.stopService(new Intent(context, DrawOverlayService.class));
        } catch (Exception ignored) { }
    }

    /** Keeps the pause/stop buttons in step with the recorder. */
    public static void refreshControls(Context context) {
        final DrawOverlayService service = instance;
        if (service == null) return;
        service.handler.post(new Runnable() {
            @Override
            public void run() {
                service.updatePauseButton();
            }
        });
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private WindowManager windowManager;
    private DrawLayer layer;
    private ImageView bubble;
    private LinearLayout panel;
    private ImageView pauseButton;
    private WindowManager.LayoutParams layerParams;
    private WindowManager.LayoutParams bubbleParams;
    private WindowManager.LayoutParams panelParams;
    private boolean panelOpen;
    private int bubbleX;
    private int bubbleY;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!canDraw(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (layer == null) show();
        return START_STICKY;
    }

    private void show() {
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        int screenHeight = getResources().getDisplayMetrics().heightPixels;

        layer = new DrawLayer(this);
        layerParams = overlayParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT);
        layerParams.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        layerParams.format = PixelFormat.TRANSLUCENT;

        bubble = new ImageView(this);
        bubble.setImageResource(R.drawable.ic_pen);
        bubble.setColorFilter(TEXT);
        bubble.setBackground(circle(PANEL, dp(28), BORDER));
        bubble.setPadding(dp(14), dp(14), dp(14), dp(14));
        bubble.setContentDescription(getString(R.string.overlay_button));
        bubbleX = screenWidth - dp(74);
        bubbleY = screenHeight - dp(260);
        bubbleParams = overlayParams(dp(56), dp(56));
        bubbleParams.x = bubbleX;
        bubbleParams.y = bubbleY;

        panel = buildPanel();
        panel.setVisibility(View.GONE);
        panelParams = overlayParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT);
        panelParams.x = bubbleX;
        panelParams.y = bubbleY;

        try {
            windowManager.addView(layer, layerParams);
            windowManager.addView(bubble, bubbleParams);
            windowManager.addView(panel, panelParams);
        } catch (Exception e) {
            stopSelf();
            return;
        }

        bubble.setOnTouchListener(new DragListener());
        bubble.setOnClickListener(v -> {
            // The touch listener decides between a tap and a drag.
        });
    }

    private LinearLayout buildPanel() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackground(round(PANEL, 18, BORDER));
        bar.setPadding(dp(6), dp(6), dp(6), dp(6));

        bar.addView(toolButton(R.drawable.ic_rect, R.string.overlay_rect, v -> pickTool(DrawLayer.TOOL_RECT)));
        bar.addView(toolButton(R.drawable.ic_circle, R.string.overlay_circle, v -> pickTool(DrawLayer.TOOL_CIRCLE)));
        bar.addView(toolButton(R.drawable.ic_trash, R.string.overlay_clear, v -> {
            layer.clear();
            stopDrawing();
        }));
        pauseButton = toolButton(R.drawable.ic_pause, R.string.action_pause, v -> {
            RecorderService.requestPause(this);
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    updatePauseButton();
                }
            }, 150);
        });
        bar.addView(pauseButton);
        bar.addView(toolButton(R.drawable.ic_stop, R.string.overlay_stop, v -> {
            RecorderService.requestStop(this);
            stopSelf();
        }));
        bar.addView(toolButton(R.drawable.ic_close, R.string.overlay_hide, v -> stopSelf()));
        return bar;
    }

    private ImageView toolButton(int iconRes, int labelRes, View.OnClickListener click) {
        ImageView button = new ImageView(this);
        button.setImageResource(iconRes);
        button.setColorFilter(TEXT);
        button.setPadding(dp(12), dp(12), dp(12), dp(12));
        button.setBackground(round(Color.parseColor("#00000000"), 14, 0));
        button.setContentDescription(getString(labelRes));
        button.setClickable(true);
        button.setFocusable(true);
        button.setOnClickListener(click);
        LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(dp(48), dp(48));
        params.setMargins(dp(1), 0, dp(1), 0);
        button.setLayoutParams(params);
        return button;
    }

    private void pickTool(int tool) {
        boolean same = layer.getTool() == tool;
        layer.setTool(same ? DrawLayer.TOOL_NONE : tool);
        updateLayerTouchable();
        updateToolHighlight();
        if (!same) {
            panelOpen = false; // the next touch draws on the screen
            panel.setVisibility(View.GONE);
        }
    }

    private void stopDrawing() {
        layer.setTool(DrawLayer.TOOL_NONE);
        updateLayerTouchable();
        updateToolHighlight();
    }

    private void updateToolHighlight() {
        if (panel == null) return;
        int tool = layer == null ? DrawLayer.TOOL_NONE : layer.getTool();
        for (int i = 0; i < 2 && i < panel.getChildCount(); i++) {
            ImageView button = (ImageView) panel.getChildAt(i);
            int icon = i == 0 ? R.drawable.ic_rect : R.drawable.ic_circle;
            int wanted = i == 0 ? DrawLayer.TOOL_RECT : DrawLayer.TOOL_CIRCLE;
            button.setColorFilter(tool == wanted ? ACTIVE : TEXT);
            button.setImageResource(icon);
        }
    }

    private void updateLayerTouchable() {
        if (layer == null || layerParams == null) return;
        boolean drawing = layer.getTool() != DrawLayer.TOOL_NONE;
        int flags = layerParams.flags;
        if (drawing) {
            flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        } else {
            flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        }
        if (flags != layerParams.flags) {
            layerParams.flags = flags;
            try {
                windowManager.updateViewLayout(layer, layerParams);
            } catch (Exception ignored) { }
        }
    }

    private void updatePauseButton() {
        if (pauseButton == null) return;
        boolean paused = RecorderService.isPaused;
        pauseButton.setImageResource(paused ? R.drawable.ic_play : R.drawable.ic_pause);
        pauseButton.setContentDescription(getString(
                paused ? R.string.action_resume : R.string.action_pause));
    }

    private void togglePanel() {
        panelOpen = !panelOpen;
        if (panelOpen) {
            updatePauseButton();
            movePanel();
            panel.setVisibility(View.VISIBLE);
            panel.setAlpha(0f);
            panel.animate().alpha(1f).setDuration(140).start();
        } else {
            panel.setVisibility(View.GONE);
            stopDrawing();
        }
    }

    private void movePanel() {
        int width = getResources().getDisplayMetrics().widthPixels;
        int panelWidth = dp(6 * 50 + 16);
        int x = Math.max(dp(4), Math.min(bubbleX, width - panelWidth - dp(4)));
        panelParams.x = x;
        panelParams.y = Math.max(dp(40), bubbleY - dp(62));
        try {
            windowManager.updateViewLayout(panel, panelParams);
        } catch (Exception ignored) { }
    }

    private WindowManager.LayoutParams overlayParams(int width, int height) {
        int type = Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                width, height, type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        return params;
    }

    /** Drags the bubble around; a simple tap opens the tools. */
    private final class DragListener implements View.OnTouchListener {
        private float downRawX;
        private float downRawY;
        private int startX;
        private int startY;
        private boolean moved;

        @Override
        public boolean onTouch(View view, MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downRawX = event.getRawX();
                    downRawY = event.getRawY();
                    startX = bubbleParams.x;
                    startY = bubbleParams.y;
                    moved = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    int dx = Math.round(event.getRawX() - downRawX);
                    int dy = Math.round(event.getRawY() - downRawY);
                    if (Math.abs(dx) > dp(6) || Math.abs(dy) > dp(6)) moved = true;
                    if (!moved) return true;
                    int width = getResources().getDisplayMetrics().widthPixels;
                    int height = getResources().getDisplayMetrics().heightPixels;
                    bubbleX = Math.max(0, Math.min(startX + dx, width - dp(56)));
                    bubbleY = Math.max(0, Math.min(startY + dy, height - dp(56)));
                    bubbleParams.x = bubbleX;
                    bubbleParams.y = bubbleY;
                    try {
                        windowManager.updateViewLayout(bubble, bubbleParams);
                    } catch (Exception ignored) { }
                    if (panelOpen) movePanel();
                    return true;
                case MotionEvent.ACTION_UP:
                    if (!moved) togglePanel();
                    return true;
                default:
                    return true;
            }
        }
    }

    @Override
    public void onDestroy() {
        instance = null;
        handler.removeCallbacksAndMessages(null);
        remove(bubble);
        remove(panel);
        remove(layer);
        bubble = null;
        panel = null;
        layer = null;
        super.onDestroy();
    }

    private void remove(View view) {
        if (view == null) return;
        try {
            windowManager.removeView(view);
        } catch (Exception ignored) { }
    }

    private GradientDrawable circle(int color, int radius, int stroke) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.OVAL);
        drawable.setColor(color);
        if (stroke != 0) drawable.setStroke(dp(1), stroke);
        return drawable;
    }

    private GradientDrawable round(int color, int radius, int stroke) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        if (stroke != 0) drawable.setStroke(dp(1), stroke);
        return drawable;
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    /**
     * The transparent canvas over the whole screen. Touches only reach it while
     * a tool is picked, so the phone stays usable the rest of the time.
     */
    public static class DrawLayer extends View {

        public static final int TOOL_NONE = 0;
        public static final int TOOL_RECT = 1;
        public static final int TOOL_CIRCLE = 2;

        private static final int MAX_SHAPES = 300;

        private static final class Shape {
            final int tool;
            final float startX;
            final float startY;
            float endX;
            float endY;

            Shape(int tool, float startX, float startY, float endX, float endY) {
                this.tool = tool;
                this.startX = startX;
                this.startY = startY;
                this.endX = endX;
                this.endY = endY;
            }
        }

        private final List<Shape> shapes = new ArrayList<>();
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private Shape current;
        private int tool = TOOL_NONE;

        DrawLayer(Context context) {
            super(context);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(context.getResources().getDisplayMetrics().density * 4f);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setColor(INK);
            setBackgroundColor(Color.TRANSPARENT);
        }

        int getTool() {
            return tool;
        }

        void setTool(int newTool) {
            tool = newTool;
            if (newTool == TOOL_NONE) current = null;
            invalidate();
        }

        void clear() {
            shapes.clear();
            current = null;
            invalidate();
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (tool == TOOL_NONE) return false;
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    current = new Shape(tool, event.getX(), event.getY(),
                            event.getX(), event.getY());
                    invalidate();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (current != null) {
                        current.endX = event.getX();
                        current.endY = event.getY();
                        invalidate();
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (current != null) {
                        current.endX = event.getX();
                        current.endY = event.getY();
                        if (Math.abs(current.endX - current.startX) > dp(6)
                                || Math.abs(current.endY - current.startY) > dp(6)) {
                            if (shapes.size() >= MAX_SHAPES) shapes.remove(0);
                            shapes.add(current);
                        }
                        current = null;
                        tool = TOOL_NONE;
                        invalidate();
                        if (getContext() instanceof DrawOverlayService) {
                            ((DrawOverlayService) getContext()).onShapeDrawn();
                        }
                    }
                    return true;
                default:
                    return false;
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            for (Shape shape : shapes) drawShape(canvas, shape);
            if (current != null) drawShape(canvas, current);
        }

        private void drawShape(Canvas canvas, Shape shape) {
            float left = Math.min(shape.startX, shape.endX);
            float top = Math.min(shape.startY, shape.endY);
            float right = Math.max(shape.startX, shape.endX);
            float bottom = Math.max(shape.startY, shape.endY);
            if (shape.tool == TOOL_CIRCLE) {
                canvas.drawOval(new RectF(left, top, right, bottom), paint);
            } else {
                canvas.drawRect(left, top, right, bottom, paint);
            }
        }

        private float dp(float value) {
            return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                    getResources().getDisplayMetrics());
        }
    }

    /** One shape is down: back to normal touching, tools close. */
    void onShapeDrawn() {
        updateLayerTouchable();
        updateToolHighlight();
        panelOpen = false;
        if (panel != null) panel.setVisibility(View.GONE);
    }
}
