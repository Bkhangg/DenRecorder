package com.bkggds.denrecorder;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
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
 * The little floating button that shows while recording: it draws a pencil
 * stroke, a rectangle or a circle straight onto the screen (so they end up in
 * the video) and holds pause and stop for the recording.
 *
 * It lives in the recorder's process, so the recording's foreground service
 * keeps it alive; drawing is only possible after the "display over other apps"
 * permission was given.
 */
public class DrawOverlayService extends Service {

    public static final String ACTION_START = "com.bkggds.denrecorder.overlay.START";
    public static final String ACTION_STOP = "com.bkggds.denrecorder.overlay.STOP";

    /** Sizes of the floating button, picked in settings. */
    public static final int[] BUBBLE_DP = {44, 58, 74, 92};
    public static final int[] TOOL_DP = {42, 50, 60, 72};

    /** The ink colors the drawing tools can use, in the settings' order. */
    public static final int[] PALETTE = {
            Color.parseColor("#F43F5E"),   // rose
            Color.parseColor("#EF4444"),   // red
            Color.parseColor("#F97316"),   // orange
            Color.parseColor("#FBBF24"),   // amber
            Color.parseColor("#22C55E"),   // green
            Color.parseColor("#14B8A6"),   // teal
            Color.parseColor("#0EA5E9"),   // sky
            Color.parseColor("#3B82F6"),   // blue
            Color.parseColor("#A855F7"),   // purple
            Color.parseColor("#EC4899"),   // pink
            Color.parseColor("#FFFFFF"),   // white
            Color.parseColor("#111111")    // black
    };

    public static final int DEFAULT_INK = PALETTE[0];
    private static final int INK = DEFAULT_INK;
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

    public static int sizeIndex(Context context) {
        int size = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                .getInt("drawSize", 1);
        return Math.max(0, Math.min(BUBBLE_DP.length - 1, size));
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

    /** The colour picked in settings; the drawing tools use it for new shapes. */
    public static int inkColor(Context context) {
        int color = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                .getInt("drawColor", DEFAULT_INK);
        return color == 0 ? DEFAULT_INK : color;
    }

    /** Applies a new ink colour without waiting for the next recording. */
    public static void refreshColor(Context context) {
        final DrawOverlayService service = instance;
        if (service == null) return;
        service.handler.post(new Runnable() {
            @Override
            public void run() {
                if (service.layer != null) service.layer.setInk(inkColor(service));
                service.updatePalette();
            }
        });
    }

    /** Applies a new button size without waiting for the next recording. */
    public static void refreshSize(Context context) {
        final DrawOverlayService service = instance;
        if (service == null) return;
        service.handler.post(new Runnable() {
            @Override
            public void run() {
                service.applySize();
            }
        });
    }

    /** Colours of the quick palette, named by the strings in the same order. */
    private static final int[] PALETTE_NAMES = {
            R.string.draw_color_rose, R.string.draw_color_red,
            R.string.draw_color_orange, R.string.draw_color_amber,
            R.string.draw_color_green, R.string.draw_color_teal,
            R.string.draw_color_sky, R.string.draw_color_blue,
            R.string.draw_color_purple, R.string.draw_color_pink,
            R.string.draw_color_white, R.string.draw_color_black
    };
    private static final int SWATCH_COLUMNS = 6;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private WindowManager windowManager;
    private DrawLayer layer;
    private ImageView bubble;
    private LinearLayout panel;
    private LinearLayout toolbar;
    private LinearLayout palettePanel;
    private ImageView pauseButton;
    private final List<ImageView> swatches = new ArrayList<>();
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
        int size = BUBBLE_DP[sizeIndex(this)];

        layer = new DrawLayer(this);
        layer.setInk(inkColor(this));
        layerParams = overlayParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT);
        layerParams.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        layerParams.format = PixelFormat.TRANSLUCENT;

        bubble = new ImageView(this);
        bubble.setImageResource(R.drawable.ic_pen);
        bubble.setColorFilter(TEXT);
        bubble.setContentDescription(getString(R.string.overlay_button));
        styleBubble(size);

        bubbleX = screenWidth - dp(size + 18);
        bubbleY = screenHeight - dp(260);
        bubbleParams = overlayParams(dp(size), dp(size));
        bubbleParams.x = bubbleX;
        bubbleParams.y = bubbleY;

        panel = buildPanel();
        panel.setVisibility(View.GONE);
        panelParams = overlayParams(1, 1);
        measurePanel();
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

        // Picking a colour or a tool then touching the screen itself should hide
        // the bar and draw right away, without a second tap.
        layer.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View view, MotionEvent event) {
                if (panelOpen && event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    closePanel();
                }
                return false;
            }
        });

        // A soft pop-in instead of the button just being there.
        bubble.setScaleX(0.5f);
        bubble.setScaleY(0.5f);
        bubble.setAlpha(0f);
        bubble.animate()
                .scaleX(1f)
                .scaleY(1f)
                .alpha(1f)
                .setDuration(220)
                .setInterpolator(new android.view.animation.OvershootInterpolator(1.5f))
                .start();
    }

    private void styleBubble(int size) {
        int pad = Math.round(size * 0.25f);
        bubble.setPadding(dp(pad), dp(pad), dp(pad), dp(pad));
        bubble.setBackground(circle(PANEL, size / 2, BORDER));
    }

    private LinearLayout buildPanel() {
        int button = TOOL_DP[sizeIndex(this)];
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setBackground(round(PANEL, 18, BORDER));
        container.setPadding(dp(5), dp(5), dp(5), dp(5));

        toolbar = new LinearLayout(this);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        container.addView(toolbar);

        toolbar.addView(toolButton(R.drawable.ic_pen, R.string.overlay_pen, button, v -> pickTool(DrawLayer.TOOL_PEN)));
        toolbar.addView(toolButton(R.drawable.ic_rect, R.string.overlay_rect, button, v -> pickTool(DrawLayer.TOOL_RECT)));
        toolbar.addView(toolButton(R.drawable.ic_circle, R.string.overlay_circle, button, v -> pickTool(DrawLayer.TOOL_CIRCLE)));
        toolbar.addView(toolButton(R.drawable.ic_trash, R.string.overlay_clear, button, v -> {
            layer.clear();
            updateToolHighlight();
        }));
        pauseButton = toolButton(R.drawable.ic_pause, R.string.action_pause, button, v -> {
            RecorderService.requestPause(this);
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    updatePauseButton();
                }
            }, 150);
        });
        toolbar.addView(pauseButton);
        toolbar.addView(toolButton(R.drawable.ic_stop, R.string.overlay_stop, button, v -> {
            RecorderService.requestStop(this);
            stopSelf();
        }));
        // The chevron hides the bar and nothing else: the picked tool stays on,
        // so the next touch draws instead of tapping around on the screen.
        toolbar.addView(toolButton(R.drawable.ic_collapse, R.string.overlay_hide, button, v -> closePanel()));

        // The ink colour is pickable at any time while recording: a row of
        // colour dots sits under the tools and new shapes use the last pick.
        palettePanel = buildPalette();
        container.addView(palettePanel);
        updatePalette();
        return container;
    }

    /** Two rows of colour dots, shown under the bar while recording. */
    private LinearLayout buildPalette() {
        LinearLayout palette = new LinearLayout(this);
        palette.setOrientation(LinearLayout.VERTICAL);
        palette.setPadding(0, dp(4), 0, dp(2));

        LinearLayout row = null;
        for (int i = 0; i < PALETTE.length; i++) {
            if (i % SWATCH_COLUMNS == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER);
                palette.addView(row);
            }
            ImageView swatch = swatch(PALETTE[i], PALETTE_NAMES[i]);
            swatches.add(swatch);
            LinearLayout.LayoutParams params =
                    new LinearLayout.LayoutParams(swatchSizePx(), swatchSizePx());
            params.setMargins(dp(1), dp(1), dp(1), dp(1));
            row.addView(swatch, params);
        }
        return palette;
    }

    private ImageView swatch(final int color, int nameRes) {
        ImageView view = new ImageView(this);
        view.setContentDescription(getString(nameRes));
        view.setClickable(true);
        view.setFocusable(true);
        view.setOnClickListener(v -> {
            getSharedPreferences("settings", MODE_PRIVATE)
                    .edit().putInt("drawColor", color).apply();
            if (layer != null) layer.setInk(color);
            updatePalette();
        });
        return view;
    }

    private void updatePalette() {
        if (swatches.isEmpty()) return;
        int ink = inkColor(this);
        int pad = Math.round(swatchDp() * 0.16f);
        for (int i = 0; i < swatches.size() && i < PALETTE.length; i++) {
            ImageView view = swatches.get(i);
            boolean picked = PALETTE[i] == ink;
            GradientDrawable dot = new GradientDrawable();
            dot.setShape(GradientDrawable.OVAL);
            dot.setColor(PALETTE[i]);
            dot.setStroke(dp(picked ? 3 : 1),
                    picked ? TEXT : (PALETTE[i] == 0xFFFFFFFF ? BORDER : 0x66FFFFFF));
            view.setImageDrawable(dot);
            view.setPadding(dp(pad), dp(pad), dp(pad), dp(pad));
        }
    }

    private int swatchDp() {
        int button = TOOL_DP[sizeIndex(this)];
        return Math.max(30, Math.round(button * 0.74f));
    }

    private int swatchSizePx() {
        return dp(swatchDp());
    }

    private ImageView toolButton(int iconRes, int labelRes, int sizeDp,
                                 View.OnClickListener click) {
        ImageView button = new ImageView(this);
        if (iconRes != 0) {
            button.setImageResource(iconRes);
            button.setColorFilter(TEXT);
        }
        int pad = Math.round(sizeDp * 0.21f);
        button.setPadding(dp(pad), dp(pad), dp(pad), dp(pad));
        button.setBackground(round(Color.parseColor("#00000000"), 14, 0));
        button.setContentDescription(getString(labelRes));
        button.setClickable(true);
        button.setFocusable(true);
        button.setOnClickListener(click);
        LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp));
        params.setMargins(dp(1), 0, dp(1), 0);
        button.setLayoutParams(params);
        return button;
    }

    /**
     * Picks a tool and keeps it. The bar stays open, so a colour can be picked
     * right after; tapping the screen beside the bar (or the chevron) hides it
     * and the touch draws straight away.
     */
    private void pickTool(int tool) {
        boolean same = layer.getTool() == tool;
        layer.setTool(same ? DrawLayer.TOOL_NONE : tool);
        updateLayerTouchable();
        updateToolHighlight();
    }

    private void updateToolHighlight() {
        if (toolbar == null) return;
        int tool = layer == null ? DrawLayer.TOOL_NONE : layer.getTool();
        for (int i = 0; i < 3 && i < toolbar.getChildCount(); i++) {
            ImageView button = (ImageView) toolbar.getChildAt(i);
            int wanted = i == 0 ? DrawLayer.TOOL_PEN
                    : i == 1 ? DrawLayer.TOOL_RECT : DrawLayer.TOOL_CIRCLE;
            button.setColorFilter(tool == wanted ? ACTIVE : TEXT);
        }
    }

    private void updateLayerTouchable() {
        if (layer == null || layerParams == null) return;
        // While the bar is open the layer takes the touches too: a tap beside
        // the bar closes it, and (with a tool picked) starts the stroke at once.
        boolean drawing = layer.getTool() != DrawLayer.TOOL_NONE || panelOpen;
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

    /** The button size can be changed in settings and applies right away. */
    private void applySize() {
        if (bubble == null || bubbleParams == null) return;
        int size = BUBBLE_DP[sizeIndex(this)];
        styleBubble(size);
        bubbleParams.width = dp(size);
        bubbleParams.height = dp(size);
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        bubbleX = Math.max(0, Math.min(bubbleX, screenWidth - bubbleParams.width));
        bubbleParams.x = bubbleX;
        try {
            windowManager.updateViewLayout(bubble, bubbleParams);
        } catch (Exception ignored) { }

        int tool = TOOL_DP[sizeIndex(this)];
        if (toolbar != null) {
            int inner = dp(tool);
            int pad = Math.round(tool * 0.21f);
            for (int i = 0; i < toolbar.getChildCount(); i++) {
                View child = toolbar.getChildAt(i);
                LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) child.getLayoutParams();
                params.width = inner;
                params.height = inner;
                child.setLayoutParams(params);
                child.setPadding(dp(pad), dp(pad), dp(pad), dp(pad));
            }
        }
        int cell = swatchSizePx();
        for (ImageView swatch : swatches) {
            LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) swatch.getLayoutParams();
            params.width = cell;
            params.height = cell;
            swatch.setLayoutParams(params);
        }
        updatePalette();
        measurePanel();
        if (panelOpen) movePanel();
    }

    private void togglePanel() {
        if (panelOpen) {
            closePanel();
            return;
        }
        panelOpen = true;
        updatePauseButton();
        updateToolHighlight();
        updatePalette();
        measurePanel();
        movePanel();
        updateLayerTouchable();
        panel.setVisibility(View.VISIBLE);
        panel.setAlpha(0f);
        panel.setScaleX(0.9f);
        panel.setScaleY(0.9f);
        panel.setTranslationY(dp(8));
        panel.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .translationY(0f)
                .setDuration(170)
                .setInterpolator(new android.view.animation.DecelerateInterpolator())
                .start();
    }

    /** Hides the bar without touching the picked tool. */
    private void closePanel() {
        if (!panelOpen) return;
        panelOpen = false;
        if (panel != null) panel.setVisibility(View.GONE);
        updateLayerTouchable();
    }

    /**
     * The bar is a WRAP_CONTENT window, so its size is measured here and given
     * to the window as a number of pixels: a wrap window that grows (the colour
     * row) otherwise keeps a stale frame, and taps land off the buttons.
     */
    private void measurePanel() {
        if (panel == null || panelParams == null) return;
        int width = getResources().getDisplayMetrics().widthPixels;
        panel.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int measuredWidth = Math.max(1, panel.getMeasuredWidth());
        int measuredHeight = Math.max(1, panel.getMeasuredHeight());
        if (panelParams.width == measuredWidth && panelParams.height == measuredHeight) return;
        panelParams.width = measuredWidth;
        panelParams.height = measuredHeight;
        try {
            windowManager.updateViewLayout(panel, panelParams);
        } catch (Exception ignored) { }
    }

    private void movePanel() {
        if (panelParams == null) return;
        int width = getResources().getDisplayMetrics().widthPixels;
        int x = Math.max(dp(4), Math.min(bubbleX, width - panelParams.width - dp(4)));
        panelParams.x = x;
        panelParams.y = Math.max(dp(40), bubbleY - panelParams.height - dp(10));
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
                    bubbleX = Math.max(0, Math.min(startX + dx, width - bubbleParams.width));
                    bubbleY = Math.max(0, Math.min(startY + dy, height - bubbleParams.height));
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
        toolbar = null;
        palettePanel = null;
        swatches.clear();
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
        public static final int TOOL_PEN = 3;

        private static final int MAX_SHAPES = 200;

        private static final class Shape {
            final int tool;
            final int color;
            final float startX;
            final float startY;
            float endX;
            float endY;
            final Path path;
            float lastX;
            float lastY;

            Shape(int tool, int color, float startX, float startY, float endX, float endY) {
                this.tool = tool;
                this.color = color;
                this.startX = startX;
                this.startY = startY;
                this.endX = endX;
                this.endY = endY;
                this.path = tool == TOOL_PEN ? new Path() : null;
                if (path != null) {
                    path.moveTo(startX, startY);
                    path.lineTo(startX + 0.5f, startY + 0.5f);
                    lastX = startX + 0.5f;
                    lastY = startY + 0.5f;
                }
            }

            void extendTo(float x, float y) {
                if (path != null) {
                    path.quadTo(lastX, lastY, (lastX + x) / 2f, (lastY + y) / 2f);
                    lastX = x;
                    lastY = y;
                }
                endX = x;
                endY = y;
            }
        }

        private final List<Shape> shapes = new ArrayList<>();
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private Shape current;
        private int tool = TOOL_NONE;
        private int ink = INK;

        DrawLayer(Context context) {
            super(context);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(context.getResources().getDisplayMetrics().density * 4f);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setColor(ink);
            setBackgroundColor(Color.TRANSPARENT);
        }

        /** New strokes use this colour; what is already drawn keeps its own. */
        void setInk(int color) {
            ink = color;
        }

        int getTool() {
            return tool;
        }

        void setTool(int newTool) {
            tool = newTool;
            current = null;
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
                    current = new Shape(tool, ink, event.getX(), event.getY(),
                            event.getX(), event.getY());
                    invalidate();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (current != null) {
                        current.extendTo(event.getX(), event.getY());
                        invalidate();
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (current != null) {
                        current.extendTo(event.getX(), event.getY());
                        boolean big = Math.abs(current.endX - current.startX) > dp(6)
                                || Math.abs(current.endY - current.startY) > dp(6);
                        if (big) {
                            if (shapes.size() >= MAX_SHAPES) shapes.remove(0);
                            shapes.add(current);
                        }
                        current = null;
                        invalidate();
                        // The tool stays picked, so the next shape can be drawn
                        // right away without another tap on the button.
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
            paint.setColor(shape.color);
            if (shape.tool == TOOL_PEN) {
                if (shape.path != null) canvas.drawPath(shape.path, paint);
                return;
            }
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
}
