package com.bkggds.denrecorder;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Chooses the part of the screen to record, then records it.
 *
 * It is a floating frame rather than a screen of its own: the user opens it,
 * goes to the app or game they want, and drags the frame over what should end
 * up in the video. "Save" stores the frame as parts of the screen, so it still
 * fits when the recording is measured in another rotation, and the ball turns
 * into a record button: tap to start, tap again to stop, hold to change the
 * frame. The ball is kept out of the video by securing its window.
 */
public class RegionPickerService extends Service {

    private static final String ACTION_START = "com.bkggds.denrecorder.region.START";
    /** Sent when a frame was saved, so the settings row can show it at once. */
    public static final String ACTION_SAVED = "com.bkggds.denrecorder.region.SAVED";
    private static final String ACTION_STOP = "com.bkggds.denrecorder.region.STOP";
    /** Recording started (true) or ended (false): the ball becomes a stop button. */
    private static final String ACTION_RECORDING = "com.bkggds.denrecorder.region.RECORDING";
    private static final String EXTRA_ON = "on";

    private static final String CHANNEL_ID = "region";
    private static final int NOTIF_ID = 42;

    private static final int PANEL = Color.parseColor("#EE111113");
    private static final int BORDER = Color.parseColor("#F43F5E");
    private static final int TEXT = Color.parseColor("#FAFAFA");
    private static final int DIM = 0xAA000000;

    private static volatile boolean running;

    private static final int BUBBLE_DP = 54;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private WindowManager windowManager;
    private ImageView bubble;
    private FrameLayout editor;
    private RegionView regionView;
    private WindowManager.LayoutParams bubbleParams;
    private WindowManager.LayoutParams editorParams;
    private boolean editorOpen;
    private boolean recording;
    private boolean foreground;
    private boolean ballMoved;
    private int bubbleX;
    private int bubbleY;
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

    /** A recording began: the ball turns into the stop button. */
    public static void recordingStarted(Context context) {
        if (!running) return;
        try {
            context.startService(new Intent(context, RegionPickerService.class)
                    .setAction(ACTION_RECORDING).putExtra(EXTRA_ON, true));
        } catch (Exception ignored) { }
    }

    /** The recording ended: the picker closes itself, ball and notification included. */
    public static void recordingStopped(Context context) {
        if (!running) return;
        try {
            context.startService(new Intent(context, RegionPickerService.class)
                    .setAction(ACTION_RECORDING).putExtra(EXTRA_ON, false));
        } catch (Exception ignored) { }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (intent != null && ACTION_RECORDING.equals(intent.getAction())) {
            setRecording(intent.getBooleanExtra(EXTRA_ON, true));
            return START_NOT_STICKY;
        }
        if (bubble == null) {
            show();
        } else if (intent != null && ACTION_START.equals(intent.getAction())) {
            // Started again from the settings row: show the frame to adjust.
            openEditor();
        }
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        running = false;
        handler.removeCallbacksAndMessages(null);
        if (windowManager != null) {
            try {
                if (editorOpen && editor != null) windowManager.removeViewImmediate(editor);
            } catch (Exception ignored) { }
            try {
                if (bubble != null) windowManager.removeViewImmediate(bubble);
            } catch (Exception ignored) { }
        }
        bubble = null;
        editor = null;
        regionView = null;
        editorOpen = false;
        recording = false;
        if (foreground) {
            foreground = false;
            try { stopForeground(true); } catch (Exception ignored) { }
        }
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

        // Bóng nổi: sau khi lưu khung, chạm để quay/dừng và giữ để đổi khung.
        int size = dp(BUBBLE_DP);
        bubble = new ImageView(this);
        bubble.setImageResource(R.drawable.ic_rect);
        bubble.setColorFilter(TEXT);
        bubble.setContentDescription(getString(R.string.region_ball));
        bubble.setPadding(size / 4, size / 4, size / 4, size / 4);
        bubble.setBackground(circle(PANEL, size / 2));

        android.content.SharedPreferences prefs =
                getSharedPreferences("settings", MODE_PRIVATE);
        bubbleX = prefs.getInt("regionBallX", screenWidth - dp(18 + BUBBLE_DP));
        bubbleY = prefs.getInt("regionBallY", Math.round(screenHeight * 0.62f));
        bubbleX = Math.max(0, Math.min(bubbleX, screenWidth - size));
        bubbleY = Math.max(0, Math.min(bubbleY, screenHeight - size));
        bubbleParams = overlayParams(size, size);
        bubbleParams.x = bubbleX;
        bubbleParams.y = bubbleY;

        editorParams = overlayParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT);

        startInForeground();

        try {
            windowManager.addView(bubble, bubbleParams);
            running = true;
        } catch (Exception e) {
            running = false;
            Toast.makeText(this, R.string.region_failed, Toast.LENGTH_SHORT).show();
            stopSelf();
            return;
        }
        bubble.setOnTouchListener(new DragListener());

        // Mở khung ngay để thấy việc cần làm; bóng vẫn nằm trên để ẩn đi khi
        // muốn chuyển app.
        openEditor();

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

    /**
     * A ball that must survive while the user records another app needs a
     * foreground service: the system stops ordinary background services after
     * a short while. The notification also says what the ball does.
     */
    private void startInForeground() {
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationManager nm =
                        (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                        getString(R.string.region_channel), NotificationManager.IMPORTANCE_LOW);
                channel.setShowBadge(false);
                channel.setSound(null, null);
                nm.createNotificationChannel(channel);
            }
            Notification notification = buildNotification();
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIF_ID, notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(NOTIF_ID, notification);
            }
            foreground = true;
        } catch (Exception ignored) {
            // No notification permission: the ball still works while it lives.
        }
    }

    private Notification buildNotification() {
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), pendingFlags());
        PendingIntent off = PendingIntent.getService(this, 1,
                new Intent(this, RegionPickerService.class).setAction(ACTION_STOP),
                pendingFlags());

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= 26) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }
        builder.setSmallIcon(R.drawable.ic_rect)
                .setContentTitle(getString(recording
                        ? R.string.region_notification_recording
                        : R.string.region_notification_ready))
                .setContentText(getString(recording
                        ? R.string.region_stop
                        : R.string.region_notification_body))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(open)
                .setPriority(Notification.PRIORITY_LOW);
        if (Build.VERSION.SDK_INT >= 23) {
            builder.addAction(new Notification.Action.Builder(
                    android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_close),
                    getString(R.string.region_off), off).build());
        } else {
            builder.addAction(new Notification.Action.Builder(
                    R.drawable.ic_close, getString(R.string.region_off), off).build());
        }
        return builder.build();
    }

    private int pendingFlags() {
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        return flags;
    }

    private void updateNotification() {
        if (!foreground) return;
        try {
            NotificationManager nm =
                    (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            nm.notify(NOTIF_ID, buildNotification());
        } catch (Exception ignored) { }
    }

    /** Recording state: the ball turns into the stop button and steps out of the frame. */
    private void setRecording(boolean on) {
        recording = on;
        if (on) {
            closeEditor();
            moveBallOutOfRegion();
            updateBubble();
            updateNotification();
            return;
        }
        // Đã quay xong: ẩn luôn bóng chọn khung và thông báo của nó, khỏi vướng
        // màn hình khi không cần quay nữa.
        stopSelf();
    }

    /**
     * The screen is captured as it is, so the ball would end up in the video.
     * While recording it is placed just outside the chosen area; the picker
     * closes when the recording ends, so it never has to come back.
     */
    private void moveBallOutOfRegion() {
        if (bubble == null || bubbleParams == null || windowManager == null) return;
        if (ballMoved || !regionSaved()) return;
        android.content.SharedPreferences prefs =
                getSharedPreferences("settings", MODE_PRIVATE);
        int left = Math.round(prefs.getFloat("regionX", 0f) * screenWidth);
        int top = Math.round(prefs.getFloat("regionY", 0f) * screenHeight);
        int right = left + Math.round(prefs.getFloat("regionW", 1f) * screenWidth);
        int bottom = top + Math.round(prefs.getFloat("regionH", 1f) * screenHeight);

        int size = bubbleParams.width;
        int margin = dp(10);
        int x = bubbleParams.x;
        int y = bubbleParams.y;
        // Already clear of the area: leave it where the user put it.
        if (x + size <= left || x >= right || y + size <= top || y >= bottom) return;

        int nx = x;
        int ny = y;
        if (screenHeight - bottom >= size + margin) {
            ny = bottom + margin;              // under the area
        } else if (top >= size + margin) {
            ny = top - margin - size;          // over it
        } else if (screenWidth - right >= size + margin) {
            nx = right + margin;               // beside it
        } else if (left >= size + margin) {
            nx = left - margin - size;
        } else {
            // The area covers the whole screen: nothing to move to, the user
            // can still drag the ball aside while it records.
            return;
        }
        nx = Math.max(0, Math.min(nx, screenWidth - size));
        ny = Math.max(0, Math.min(ny, screenHeight - size));
        ballMoved = true;
        bubbleParams.x = nx;
        bubbleParams.y = ny;
        try {
            windowManager.updateViewLayout(bubble, bubbleParams);
        } catch (Exception ignored) { }
    }

    /** Khung chọn vùng: lớp phủ tối, viền đỏ và thanh Huỷ / Ẩn / Lưu. */
    private FrameLayout buildEditor() {
        FrameLayout root = new FrameLayout(this);
        regionView = new RegionView(this);
        root.addView(regionView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setPadding(dp(6), dp(6), dp(6), dp(6));
        bar.setBackground(round(PANEL, 20));

        TextView cancel = chip(getString(R.string.dialog_cancel), TEXT);
        cancel.setOnClickListener(v -> {
            // Hủy là thoát hẳn khỏi chế độ chọn vùng: khung, bóng và thông báo
            // cùng tắt. Muốn giữ bóng để quay thì dùng "Ẩn".
            stopSelf();
        });
        bar.addView(cancel, new LinearLayout.LayoutParams(dp(84), dp(44)));

        TextView hide = chip(getString(R.string.region_hide), TEXT);
        hide.setOnClickListener(v -> closeEditor());
        LinearLayout.LayoutParams hideLp = new LinearLayout.LayoutParams(dp(104), dp(44));
        hideLp.leftMargin = dp(6);
        bar.addView(hide, hideLp);

        TextView save = chip(getString(R.string.region_save), BORDER);
        save.setOnClickListener(v -> save());
        LinearLayout.LayoutParams saveLp = new LinearLayout.LayoutParams(dp(96), dp(44));
        saveLp.leftMargin = dp(6);
        bar.addView(save, saveLp);

        FrameLayout.LayoutParams barLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        barLp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        barLp.bottomMargin = dp(40);
        root.addView(bar, barLp);
        return root;
    }

    private void openEditor() {
        if (editorOpen || windowManager == null || bubble == null) return;
        if (editor == null) editor = buildEditor();
        try {
            windowManager.addView(editor, editorParams);
            // Bóng được thêm lại sau cùng để luôn nằm trên khung và chạm được.
            windowManager.removeViewImmediate(bubble);
            windowManager.addView(bubble, bubbleParams);
            editorOpen = true;
            updateBubble();
        } catch (Exception e) {
            editorOpen = false;
            try {
                if (editor != null) windowManager.removeViewImmediate(editor);
            } catch (Exception ignored) { }
            Toast.makeText(this, R.string.region_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private void closeEditor() {
        if (!editorOpen || editor == null || windowManager == null) return;
        try {
            windowManager.removeViewImmediate(editor);
        } catch (Exception ignored) { }
        editorOpen = false;
        // Built again from what was saved, so a cancelled change is really gone.
        editor = null;
        regionView = null;
        updateBubble();
    }

    /** Đổi hình trên bóng theo việc nó đang làm. */
    private void updateBubble() {
        if (bubble == null) return;
        int size = dp(BUBBLE_DP);
        if (editorOpen) {
            bubble.setImageResource(R.drawable.ic_collapse);
            bubble.setBackground(circle(PANEL, size / 2));
            bubble.setColorFilter(TEXT);
            bubble.setContentDescription(getString(R.string.region_hide));
        } else if (recording) {
            bubble.setImageResource(R.drawable.ic_stop);
            bubble.setBackground(circle(BORDER, size / 2));
            bubble.setColorFilter(TEXT);
            bubble.setContentDescription(getString(R.string.region_stop));
        } else if (regionSaved()) {
            // A saved frame: the ball is the record button now.
            bubble.setImageResource(R.drawable.ic_record);
            bubble.setBackground(circle(BORDER, size / 2));
            bubble.setColorFilter(TEXT);
            bubble.setContentDescription(getString(R.string.region_record));
        } else {
            bubble.setImageResource(R.drawable.ic_rect);
            bubble.setBackground(circle(PANEL, size / 2));
            bubble.setColorFilter(TEXT);
            bubble.setContentDescription(getString(R.string.region_ball));
        }
    }

    private boolean regionSaved() {
        return getSharedPreferences("settings", MODE_PRIVATE)
                .getBoolean("regionOn", false);
    }

    /** Starts a recording from the ball, without opening the app. */
    private void startRecording() {
        Intent intent = new Intent(this, CapturePromptActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        try {
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, R.string.region_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private GradientDrawable circle(int color, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setShape(GradientDrawable.OVAL);
        shape.setColor(color);
        shape.setStroke(dp(1), Color.parseColor("#3F3F46"));
        shape.setSize(radius * 2, radius * 2);
        return shape;
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

    /** Kéo bóng đi chỗ khác; chạm để quay/dừng, giữ để mở lại khung. */
    private final class DragListener implements View.OnTouchListener {
        private float downRawX;
        private float downRawY;
        private int startX;
        private int startY;
        private boolean moved;
        private boolean held;

        private final Runnable longPress = new Runnable() {
            @Override
            public void run() {
                if (moved) return;
                held = true;
                if (bubble != null) {
                    bubble.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                }
                // While recording the frame stays as it is: it is in use.
                if (!recording) openEditor();
            }
        };

        @Override
        public boolean onTouch(View view, MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downRawX = event.getRawX();
                    downRawY = event.getRawY();
                    startX = bubbleParams.x;
                    startY = bubbleParams.y;
                    moved = false;
                    held = false;
                    handler.postDelayed(longPress, ViewConfiguration.getLongPressTimeout());
                    return true;
                case MotionEvent.ACTION_MOVE:
                    int dx = Math.round(event.getRawX() - downRawX);
                    int dy = Math.round(event.getRawY() - downRawY);
                    if (Math.abs(dx) > dp(6) || Math.abs(dy) > dp(6)) {
                        moved = true;
                        handler.removeCallbacks(longPress);
                    }
                    if (!moved) return true;
                    bubbleX = Math.max(0, Math.min(startX + dx,
                            screenWidth - bubbleParams.width));
                    bubbleY = Math.max(0, Math.min(startY + dy,
                            screenHeight - bubbleParams.height));
                    bubbleParams.x = bubbleX;
                    bubbleParams.y = bubbleY;
                    try {
                        windowManager.updateViewLayout(bubble, bubbleParams);
                    } catch (Exception ignored) { }
                    return true;
                case MotionEvent.ACTION_UP:
                    handler.removeCallbacks(longPress);
                    if (moved) {
                        getSharedPreferences("settings", MODE_PRIVATE).edit()
                                .putInt("regionBallX", bubbleParams.x)
                                .putInt("regionBallY", bubbleParams.y)
                                .apply();
                        // The user placed it here: keep it there afterwards.
                        ballMoved = false;
                    } else if (!held) {
                        onTap();
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    handler.removeCallbacks(longPress);
                    return true;
                default:
                    return false;
            }
        }
    }

    /** One tap on the ball: fold the frame, record, or stop the recording. */
    private void onTap() {
        if (editorOpen) {
            closeEditor();
        } else if (recording) {
            RecorderService.requestStop(this);
        } else if (regionSaved()) {
            startRecording();
        } else {
            openEditor();
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
        try {
            sendBroadcast(new Intent(ACTION_SAVED).setPackage(getPackageName()));
        } catch (Exception ignored) { }
        // The frame is done; the ball now records and stops with one tap.
        closeEditor();
        updateBubble();
        updateNotification();
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
            canvas.drawText(getString(R.string.region_hint_ball),
                    getWidth() / 2f, dp(60) + dp(48), hint);
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
