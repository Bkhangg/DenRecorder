package com.bkggds.denrecorder;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.service.quicksettings.TileService;
import android.util.Log;

public class RecorderService extends Service {

    @Override
    protected void attachBaseContext(android.content.Context newBase) {
        super.attachBaseContext(LocaleHelper.wrap(newBase));
    }

    private static final String TAG = "RecorderService";

    /**
     * The running service, so the floating button can pause and stop it
     * directly. Starting a service from the background is restricted on newer
     * Android versions, and an overlay button lives in the background.
     */
    private static volatile RecorderService instance;

    public static final String ACTION_START = "com.bkggds.denrecorder.action.START";
    public static final String ACTION_STOP = "com.bkggds.denrecorder.action.STOP";
    public static final String ACTION_PAUSE = "com.bkggds.denrecorder.action.PAUSE";
    public static final String ACTION_RESUME = "com.bkggds.denrecorder.action.RESUME";

    public static final String EXTRA_RESULT_CODE = "resultCode";
    public static final String EXTRA_DATA = "data";
    public static final String EXTRA_AUDIO = "audio";

    public static volatile boolean isRecording;
    public static volatile boolean isPaused;
    public static volatile long startedAt;
    /** Time spent paused, so the timer shows the length of the video. */
    public static volatile long pausedTotalMs;
    private static volatile long pausedAtMs;
    public static volatile Uri lastUri;
    public static volatile String lastError;
    public static volatile String lastSummary;

    /** Length of the recording so far, paused time left out. */
    public static long elapsedMs() {
        if (!isRecording || startedAt <= 0) return 0;
        long now = System.currentTimeMillis();
        long paused = pausedTotalMs;
        if (isPaused && pausedAtMs > 0) paused += now - pausedAtMs;
        return Math.max(0, now - startedAt - paused);
    }

    private static void resetTimer() {
        startedAt = System.currentTimeMillis();
        pausedTotalMs = 0;
        pausedAtMs = 0;
    }

    private static final String CHANNEL_ID = "recording";
    private static final int NOTIF_ID = 4711;
    /** A separate, dismissable notification that stays after the recording ends. */
    private static final String CHANNEL_SAVED = "saved";
    private static final int SAVED_ID = 4712;

    /** Do not start a recording with less free space than this. */
    private static final long MIN_FREE_BYTES = 400L * 1024 * 1024;
    /** Stop a long recording before the card fills up completely. */
    private static final long LOW_SPACE_BYTES = 150L * 1024 * 1024;

    private ScreenRecorder recorder;
    private MediaProjection projection;
    private Handler handler;
    /** The once-a-second island timer gets a thread of its own: building a
     * notification and handing it to SystemUI must not take a frame away from
     * the app's own screens (or from the game the video is of). */
    private Handler islandHandler;
    private android.os.HandlerThread islandThread;
    private volatile boolean finishing;
    private volatile boolean selfStop;

    /** Keeps the display awake while a recording runs. */
    private android.os.PowerManager.WakeLock screenLock;
    private boolean screenReceiverOn;
    /** The recording was paused because the display went dark. */
    private boolean screenPaused;
    /** The projection was stopped by the display going dark, not by a failure. */
    private boolean screenOffStop;
    /** The recording is saved and the service waits for the phone to be unlocked. */
    private boolean waitingUnlock;
    /** How long a "record again" stands waiting for the phone to be unlocked. */
    private static final long WAIT_UNLOCK_MS = 30L * 60 * 1000;
    /** A line the saved dialog adds, e.g. that the screen was turned off. */
    public static volatile String lastNote;

    /**
     * Long recordings (an hour and more) must not need the app to do anything
     * every second: the notification counts time by itself (chronometer), and
     * this ticker only checks that storage is not running out.
     */
    /**
     * The island draws the time we send, not a chronometer, so it is refreshed
     * once a second while the beta feature is on. Nothing else in the app
     * updates that often.
     */
    private final Runnable islandTicker = new Runnable() {
        @Override
        public void run() {
            if (!isRecording) return;
            if (!Island.enabled(RecorderService.this)) return;
            if (Island.allowed(RecorderService.this)) updateNotification();
            Handler self = islandHandler;
            if (self != null) self.postDelayed(this, 1_000);
        }
    };

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (!isRecording) return;
            if (freeBytes() < LOW_SPACE_BYTES) {
                stopRecording(getString(R.string.rec_stopped_space));
                return;
            }
            handler.postDelayed(this, 15_000);
        }
    };

    private long freeBytes() {
        try {
            java.io.File dir = getExternalFilesDir(null);
            if (dir == null) dir = getFilesDir();
            android.os.StatFs stat = new android.os.StatFs(dir.getAbsolutePath());
            return stat.getAvailableBytes();
        } catch (Exception e) {
            return Long.MAX_VALUE;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        handler = new Handler(Looper.getMainLooper());
        islandThread = new android.os.HandlerThread("island-tick");
        islandThread.start();
        islandHandler = new Handler(islandThread.getLooper());
        createChannel();
        // The screen going dark ends a screen recording on Android: hold the
        // recording instead of letting the frames go black, and pick it up
        // again when the display is back.
        try {
            android.content.IntentFilter filter = new android.content.IntentFilter();
            filter.addAction(Intent.ACTION_SCREEN_OFF);
            filter.addAction(Intent.ACTION_SCREEN_ON);
            filter.addAction(Intent.ACTION_USER_PRESENT);
            registerReceiver(screenReceiver, filter);
            screenReceiverOn = true;
        } catch (Exception ignored) { }
    }

    /** The display went dark or came back; see {@link #onScreenOff()}. */
    private final android.content.BroadcastReceiver screenReceiver =
            new android.content.BroadcastReceiver() {
                @Override
                public void onReceive(android.content.Context context, Intent intent) {
                    if (intent == null || intent.getAction() == null) return;
                    if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) onScreenOff();
                    else if (Intent.ACTION_SCREEN_ON.equals(intent.getAction())) onScreenOn();
                    else if (Intent.ACTION_USER_PRESENT.equals(intent.getAction())) onUserPresent();
                }
            };

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopRecording(null);
            return START_NOT_STICKY;
        }
        if (ACTION_PAUSE.equals(action)) {
            pauseRecording();
            return START_NOT_STICKY;
        }
        if (ACTION_RESUME.equals(action)) {
            resumeRecording();
            return START_NOT_STICKY;
        }
        if (ACTION_START.equals(action)) {
            begin(intent);
        }
        return START_NOT_STICKY;
    }

    private void begin(Intent intent) {
        final int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED);
        Intent extra;
        if (Build.VERSION.SDK_INT >= 33) {
            extra = intent.getParcelableExtra(EXTRA_DATA, Intent.class);
        } else {
            extra = intent.getParcelableExtra(EXTRA_DATA);
        }
        final Intent data = extra;
        final int audio = intent.getIntExtra(EXTRA_AUDIO, ScreenRecorder.AUDIO_NONE);

        final android.content.SharedPreferences prefs =
                getSharedPreferences("settings", MODE_PRIVATE);

        // Keep the screen turned the way the user picked, so the video keeps one
        // orientation while it records. Their own rotation setting comes back
        // as soon as the recording ends.
        final String orientation = prefs.getString("orientation", "auto");
        final boolean rotationLocked = RotationLock.apply(this, orientation);

        int type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION;
        if (audio == ScreenRecorder.AUDIO_MIC || audio == ScreenRecorder.AUDIO_INTERNAL_MIC) {
            type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
        }
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, buildNotification(getString(R.string.notification_starting)), type);
        } else {
            startForeground(NOTIF_ID, buildNotification(getString(R.string.notification_starting)));
        }

        final Runnable capture = new Runnable() {
            @Override
            public void run() {
                startCapture(resultCode, data, audio, prefs, orientation, rotationLocked);
            }
        };
        if (rotationLocked) {
            // Turning the display takes a moment; wait for it, so the whole
            // video is recorded in the picked orientation.
            awaitRotation(orientation, 30, capture);
        } else {
            handler.post(capture);
        }
    }

    /** Runs {@code then} once the display is turned the way the user picked. */
    private void awaitRotation(final String orientation, final int tries,
                               final Runnable then) {
        if (tries <= 0 || rotationMatches(orientation)) {
            // The display reports the new rotation a moment before apps get the
            // matching screen size, so give it a beat before measuring.
            handler.postDelayed(then, 350);
            return;
        }
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                awaitRotation(orientation, tries - 1, then);
            }
        }, 100);
    }

    /**
     * True when the display is turned the chosen way and reports the matching
     * screen size, which is what the recording is measured from.
     */
    private boolean rotationMatches(String orientation) {
        int rotation = displayRotation();
        boolean sideways = rotation == android.view.Surface.ROTATION_90
                || rotation == android.view.Surface.ROTATION_270;
        if ("landscape".equals(orientation) ? !sideways : sideways) return false;
        try {
            android.util.DisplayMetrics metrics = new android.util.DisplayMetrics();
            android.view.WindowManager windowManager =
                    (android.view.WindowManager) getSystemService(WINDOW_SERVICE);
            windowManager.getDefaultDisplay().getRealMetrics(metrics);
            boolean sizeSideways = metrics.widthPixels > metrics.heightPixels;
            return "landscape".equals(orientation) ? sizeSideways : !sizeSideways;
        } catch (Exception e) {
            return true;
        }
    }

    private int displayRotation() {
        try {
            android.hardware.display.DisplayManager manager =
                    (android.hardware.display.DisplayManager) getSystemService(DISPLAY_SERVICE);
            android.view.Display display = manager == null ? null
                    : manager.getDisplay(android.view.Display.DEFAULT_DISPLAY);
            return display == null ? 0 : display.getRotation();
        } catch (Exception e) {
            return 0;
        }
    }

    private void startCapture(final int resultCode, final Intent data, final int audio,
                              final android.content.SharedPreferences prefs,
                              final String orientation, final boolean rotationLocked) {
        if (finishing) return;

        if (data == null) {
            lastError = getString(R.string.status_capture_denied);
            stopRecording(lastError);
            return;
        }

        try {
            MediaProjectionManager manager =
                    (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            projection = manager.getMediaProjection(resultCode, data);
        } catch (Exception e) {
            lastError = "Screen capture permission error: " + e.getMessage();
            stopRecording(lastError);
            return;
        }
        if (projection == null) {
            lastError = "Could not start screen capture";
            stopRecording(lastError);
            return;
        }

        if (freeBytes() < MIN_FREE_BYTES) {
            lastError = getString(R.string.start_no_space);
            stopRecording(lastError);
            return;
        }

        projection.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                if (selfStop) return; // we stopped the recording ourselves
                if (screenOff()) {
                    // Android ends the projection when the display goes dark.
                    // What was recorded is fine, so it is saved, not thrown away.
                    Log.i(TAG, "Projection stopped with the screen off: saving the video");
                    screenOffStop = true;
                    stopRecording(null);
                    return;
                }
                Log.i(TAG, "Projection stopped by system");
                stopRecording("Screen capture was stopped");
            }
        }, handler);

        lastError = null;
        lastUri = null;
        lastNote = null;
        screenPaused = false;
        screenOffStop = false;
        waitingUnlock = false;
        handler.removeCallbacks(unlockTimeout);
        // Android records the screen as it is: a display that sleeps would only
        // put black frames in the video, and cut the recording short.
        keepScreenOn();
        resetTimer();
        isRecording = true;
        handler.post(ticker);
        if (Island.enabled(this) && islandHandler != null) islandHandler.post(islandTicker);

        try {
            int resWidth = prefs.getInt("resWidth", 0);
            int fps = prefs.getInt("fps", 60);
            boolean perfMode = prefs.getBoolean("perfMode", true);
            if (perfMode && fps > 60) {
                // Keep a game next to the recorder smooth: 60 fps is enough.
                fps = 60;
            }
            int bitrate = prefs.getInt("bitrateMbps", 32) * 1_000_000;
            String codec = prefs.getString("codec", "avc");
            // In performance mode the video never grows past the screen: an
            // upscaled 4K frame costs the GPU the game needs.
            boolean capToScreen = perfMode;
            // A chosen area is recorded instead of the whole screen.
            float[] region = null;
            if (prefs.getBoolean("regionOn", false)) {
                float left = prefs.getFloat("regionX", 0f);
                float top = prefs.getFloat("regionY", 0f);
                float width = prefs.getFloat("regionW", 1f);
                float height = prefs.getFloat("regionH", 1f);
                if (width > 0.02f && height > 0.02f) {
                    region = new float[]{left, top, width, height};
                }
            }
            recorder = new ScreenRecorder(getApplicationContext(), projection, audio,
                    resWidth, fps, bitrate,
                    "hevc".equals(codec) ? ScreenRecorder.CODEC_HEVC : ScreenRecorder.CODEC_AVC,
                    rotationLocked ? orientation : "auto", capToScreen, region);
            recorder.start();
            updateNotification();
            refreshTile();
            // The frame that picks the recorded area did its job: its ball
            // becomes the stop button for this recording.
            if (RegionPickerService.isRunning()) {
                if (region != null) RegionPickerService.recordingStarted(this);
                else RegionPickerService.stop(this);
            }
            if (prefs.getBoolean("drawOverlay", true)) {
                DrawOverlayService.start(this);
            }
            // A short buzz, not a toast: the screen is recorded exactly as it
            // is, so anything drawn on it would land in the video. The
            // notification and the floating button show the same thing.
            buzz(new long[]{0, 40});
        } catch (Exception e) {
            Log.e(TAG, "Recording failed to start", e);
            lastError = "Recording failed: " + (e.getMessage() == null ? e.toString() : e.getMessage());
            stopRecording(lastError);
        }
    }

    private void stopRecording(String reason) {
        if (finishing) return;
        finishing = true;
        isRecording = false;
        handler.removeCallbacks(ticker);
        if (islandHandler != null) islandHandler.removeCallbacks(islandTicker);

        final ScreenRecorder active = recorder;
        recorder = null;
        final String failReason = reason;

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    if (active != null) {
                        active.stop();
                        active.logTimeline();
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Error while stopping", e);
                } finally {
                    // The recording is closed (or failed): give the user their
                    // rotation back, whatever happened while closing it.
                    RotationLock.release(RecorderService.this);
                }

                Uri uri = active == null ? null : active.getOutputUri();
                lastUri = uri;
                lastSummary = active == null ? null : active.describeSettings();
                if (failReason != null) {
                    lastError = failReason;
                } else if (uri == null) {
                    lastError = "Nothing was recorded";
                } else {
                    lastError = null;
                }

                if (projection != null) {
                    selfStop = true;
                    try { projection.stop(); } catch (Exception ignored) { }
                    projection = null;
                }

                DrawOverlayService.stop(RecorderService.this);
                isPaused = false;
                screenPaused = false;
                // The display was turned off and the user asked for a new
                // recording once the phone is back: the saved card says so, and
                // the service stays on until the phone is unlocked.
                final boolean resumeWanted = screenOffStop && uri != null && resumeWhenAwake();
                lastNote = screenOffStop
                        ? getString(resumeWanted
                                ? R.string.saved_screen_off_resume : R.string.saved_screen_off)
                        : null;
                letScreenSleep();

                final Uri savedUri = uri;
                final String savedError = failReason != null ? failReason : lastError;
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        finishing = false;
                        refreshTile();
                        // The recording is over: the ball is a record button again.
                        RegionPickerService.recordingStopped(RecorderService.this);
                        if (savedError != null) {
                            toast(savedError);
                            notifySaved(null, savedError);
                        } else if (savedUri != null) {
                            toast(getString(R.string.toast_recording_saved));
                            notifySaved(savedUri, null);
                        }
                        // Two short pulses: the video is closed, so the toast
                        // above is shown after the capture has ended.
                        buzz(new long[]{0, 40, 90, 40});
                        if (resumeWanted) {
                            waitForUnlock();
                        } else {
                            try { stopForeground(true); } catch (Exception ignored) { }
                            stopSelf();
                        }
                    }
                });
            }
        }, "rec-stop").start();
    }

    /** True when the display is dark (the phone is asleep or locked). */
    private boolean screenOff() {
        try {
            android.os.PowerManager pm =
                    (android.os.PowerManager) getSystemService(POWER_SERVICE);
            return pm != null && !pm.isInteractive();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Keeps the display awake for as long as a recording runs. The screen is
     * what is being recorded, so it sleeping is never wanted: the video would
     * only hold black frames, and Android ends the capture.
     */
    private void keepScreenOn() {
        try {
            android.os.PowerManager pm =
                    (android.os.PowerManager) getSystemService(POWER_SERVICE);
            if (pm == null) return;
            if (screenLock == null) {
                // Dim, so a long recording does not burn the battery at full
                // brightness; the screen still stays on.
                screenLock = pm.newWakeLock(android.os.PowerManager.SCREEN_DIM_WAKE_LOCK,
                        "DenRecorder:recording");
                screenLock.setReferenceCounted(false);
            }
            screenLock.acquire();
        } catch (Exception e) {
            Log.w(TAG, "Could not keep the screen on", e);
        }
    }

    private void letScreenSleep() {
        try {
            if (screenLock != null && screenLock.isHeld()) screenLock.release();
        } catch (Exception ignored) { }
    }

    /** True when the user picked "record again" for when the display comes back. */
    private boolean resumeWhenAwake() {
        return "resume".equals(getSharedPreferences("settings", MODE_PRIVATE)
                .getString("screenOffAction", "stop"));
    }

    /**
     * Holds the service (with its notification) while the phone is asleep, so a
     * new recording can start on its own as soon as it is unlocked.
     */
    private void waitForUnlock() {
        waitingUnlock = true;
        handler.removeCallbacks(unlockTimeout);
        handler.postDelayed(unlockTimeout, WAIT_UNLOCK_MS);
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            nm.notify(NOTIF_ID, buildWaitNotification());
        } catch (Exception e) {
            Log.w(TAG, "Could not post the waiting notification", e);
        }
    }

    /** The wait is over: the same file list ends here, no new recording. */
    private final Runnable unlockTimeout = new Runnable() {
        @Override
        public void run() {
            if (!waitingUnlock) return;
            Log.i(TAG, "Nobody unlocked the phone: no new recording");
            waitingUnlock = false;
            try { stopForeground(true); } catch (Exception ignored) { }
            stopSelf();
        }
    };

    /** The phone is unlocked: a new recording starts, the user asks nothing. */
    private void onUserPresent() {
        if (!waitingUnlock) return;
        Log.i(TAG, "Unlocked: starting a new recording");
        waitingUnlock = false;
        handler.removeCallbacks(unlockTimeout);
        try { stopForeground(true); } catch (Exception ignored) { }
        try {
            startActivity(new Intent(this, CapturePromptActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            Log.w(TAG, "Could not start the recording again", e);
        }
        stopSelf();
    }

    private Notification buildWaitNotification() {
        PendingIntent resume = PendingIntent.getActivity(this, 5,
                new Intent(this, CapturePromptActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), pendingFlags());
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= 26) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }
        return builder.setSmallIcon(R.drawable.ic_record)
                .setContentTitle(getString(R.string.wait_unlock_title))
                .setContentText(getString(R.string.wait_unlock_text))
                .setContentIntent(resume)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(Notification.PRIORITY_LOW)
                .addAction(new Notification.Action.Builder(
                        android.R.drawable.ic_media_play,
                        getString(R.string.notification_resume), resume).build())
                .build();
    }

    /** The display went dark: hold the video instead of recording black frames. */
    private void onScreenOff() {
        if (!isRecording || recorder == null || isPaused) return;
        Log.i(TAG, "Screen off: pausing the recording until it is back");
        screenPaused = true;
        pauseRecording();
    }

    /** The display is back: the same file goes on, without the dark stretch. */
    private void onScreenOn() {
        if (!screenPaused) return;
        screenPaused = false;
        if (isRecording && isPaused) {
            Log.i(TAG, "Screen on: resuming the recording");
            resumeRecording();
        }
    }

    /** Holds the video without ending it, so one file stays one recording. */
    public void pauseRecording() {
        if (!isRecording || recorder == null || isPaused) return;
        isPaused = true;
        pausedAtMs = System.currentTimeMillis();
        try {
            recorder.pause();
        } catch (Exception e) {
            Log.w(TAG, "Could not pause", e);
        }
        updateNotification();
        DrawOverlayService.refreshControls(this);
        buzz(new long[]{0, 25});
    }

    public void resumeRecording() {
        if (!isRecording || recorder == null || !isPaused) return;
        if (pausedAtMs > 0) {
            pausedTotalMs += System.currentTimeMillis() - pausedAtMs;
            pausedAtMs = 0;
        }
        isPaused = false;
        try {
            recorder.resume();
        } catch (Exception e) {
            Log.w(TAG, "Could not resume", e);
        }
        updateNotification();
        DrawOverlayService.refreshControls(this);
        buzz(new long[]{0, 25});
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(ticker);
        if (islandHandler != null) islandHandler.removeCallbacks(islandTicker);
        if (islandThread != null) {
            islandThread.quitSafely();
            islandThread = null;
            islandHandler = null;
        }
        isRecording = false;
        isPaused = false;
        screenPaused = false;
        if (screenReceiverOn) {
            try { unregisterReceiver(screenReceiver); } catch (Exception ignored) { }
            screenReceiverOn = false;
        }
        letScreenSleep();
        // A killed service must not leave a "stop" ball behind on the screen.
        RegionPickerService.recordingStopped(this);
        DrawOverlayService.stop(this);
        // Safety net: never leave the screen locked after a killed service.
        RotationLock.release(this);
        if (instance == this) instance = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, getString(R.string.notification_channel),
                NotificationManager.IMPORTANCE_LOW);
        channel.setShowBadge(false);
        channel.setSound(null, null);
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.createNotificationChannel(channel);
        createSavedChannel();
    }

    private void createSavedChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_SAVED, getString(R.string.notification_channel_saved),
                NotificationManager.IMPORTANCE_DEFAULT);
        channel.setShowBadge(true);
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.createNotificationChannel(channel);
    }

    /**
     * The recording notification goes away when the file is closed, so the stop
     * is confirmed in the notification shade as well: tapping it opens the new
     * video in the player.
     */
    private void notifySaved(Uri uri, String error) {
        try {
            createSavedChannel();
            Intent open;
            if (uri != null) {
                open = new Intent(this, PlayerActivity.class)
                        .setData(uri)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                                | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } else {
                open = new Intent(this, MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            }
            PendingIntent content = PendingIntent.getActivity(this, 4, open, pendingFlags());

            Notification.Builder builder;
            if (Build.VERSION.SDK_INT >= 26) {
                builder = new Notification.Builder(this, CHANNEL_SAVED);
            } else {
                builder = new Notification.Builder(this);
            }
            String folder = getSharedPreferences("settings", MODE_PRIVATE)
                    .getString("saveFolder", "Movies/Screen Recorder");
            builder.setSmallIcon(uri != null ? R.drawable.ic_check
                            : android.R.drawable.stat_notify_error)
                    .setContentTitle(getString(uri != null
                            ? R.string.notification_saved_title
                            : R.string.failed_title))
                    .setContentText(uri != null
                            ? getString(R.string.notification_saved_text, folder)
                            : String.valueOf(error))
                    .setContentIntent(content)
                    .setAutoCancel(true)
                    .setShowWhen(true)
                    .setWhen(System.currentTimeMillis())
                    .setCategory(Notification.CATEGORY_STATUS)
                    .setPriority(Notification.PRIORITY_DEFAULT);
            if (uri != null && (lastSummary != null || lastNote != null)) {
                String text = getString(R.string.notification_saved_text, folder);
                if (lastSummary != null) text = text + " · " + lastSummary;
                if (lastNote != null) text = text + "\n" + lastNote;
                builder.setStyle(new Notification.BigTextStyle().bigText(text));
            }
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            nm.notify(SAVED_ID, builder.build());
        } catch (Exception ignored) { }
    }

    private int pendingFlags() {
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        return flags;
    }

    private Notification buildNotification(String text) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0, open, pendingFlags());

        Intent stop = new Intent(this, RecorderService.class).setAction(ACTION_STOP);
        PendingIntent stopIntent = PendingIntent.getService(this, 1, stop, pendingFlags());

        Intent hold = new Intent(this, RecorderService.class)
                .setAction(isPaused ? ACTION_RESUME : ACTION_PAUSE);
        PendingIntent holdIntent = PendingIntent.getService(this, 2, hold, pendingFlags());

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= 26) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }
        builder.setSmallIcon(android.R.drawable.presence_video_online)
                .setContentTitle(getString(R.string.notification_title))
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                // The system counts the recording time itself, so a one hour
                // recording needs no notification update every second.
                .setShowWhen(isRecording)
                .setWhen(startedAt > 0 ? startedAt : System.currentTimeMillis())
                .setUsesChronometer(isRecording)
                .setContentIntent(contentIntent)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setPriority(Notification.PRIORITY_LOW);

        Notification.Action stopAction;
        // A filled square, not a cross: the cross reads as "close" (and the
        // island shows the very same action), a square reads as "stop".
        if (Build.VERSION.SDK_INT >= 23) {
            stopAction = new Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_stop),
                    getString(R.string.notification_stop), stopIntent).build();
        } else {
            stopAction = new Notification.Action.Builder(
                    R.drawable.ic_stop,
                    getString(R.string.notification_stop), stopIntent).build();
        }
        int holdIcon = isPaused ? R.drawable.ic_play : R.drawable.ic_pause;
        Notification.Action holdAction;
        if (Build.VERSION.SDK_INT >= 23) {
            holdAction = new Notification.Action.Builder(
                    Icon.createWithResource(this, holdIcon),
                    getString(isPaused ? R.string.action_resume : R.string.action_pause),
                    holdIntent).build();
        } else {
            holdAction = new Notification.Action.Builder(
                    holdIcon,
                    getString(isPaused ? R.string.action_resume : R.string.action_pause),
                    holdIntent).build();
        }
        builder.addAction(holdAction);
        builder.addAction(stopAction);
        Notification notification = builder.build();
        // Beta: the same notification can live in Xiaomi's Super Island.
        if (Island.enabled(this) && Island.allowed(this)) {
            long elapsed = elapsedMs();
            String time = String.format(java.util.Locale.US, "%02d:%02d",
                    elapsed / 60000L, (elapsed / 1000L) % 60L);
            // HyperOS counts the island timer up from this moment on its own,
            // so paused time is simply left out of it.
            long timerFrom = System.currentTimeMillis() - elapsed;
            Island.apply(this, notification,
                    getString(isPaused ? R.string.island_paused : R.string.island_recording),
                    time, timerFrom, isPaused);
        }
        return notification;
    }

    private void updateNotification() {
        if (!isRecording || recorder == null || finishing) return;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        try {
            Notification notification = buildNotification(
                    (isPaused ? getString(R.string.notification_paused) + " · " : "")
                            + recorder.describeSettings());
            // The island timer updates on its own thread, so a stop can land
            // between building this and showing it: never leave a "recording"
            // notification behind after the recording ended.
            if (finishing || !isRecording) return;
            nm.notify(NOTIF_ID, notification);
        } catch (Exception ignored) { }
    }

    public static void requestPause(android.content.Context context) {
        final RecorderService service = instance;
        if (service != null) {
            service.handler.post(new Runnable() {
                @Override
                public void run() {
                    if (isPaused) service.resumeRecording();
                    else service.pauseRecording();
                }
            });
            return;
        }
        try {
            context.startService(new Intent(context, RecorderService.class)
                    .setAction(isPaused ? ACTION_RESUME : ACTION_PAUSE));
        } catch (Exception ignored) { }
    }

    public static void requestStop(android.content.Context context) {
        final RecorderService service = instance;
        if (service != null) {
            service.handler.post(new Runnable() {
                @Override
                public void run() {
                    service.stopRecording(null);
                }
            });
            return;
        }
        Intent intent = new Intent(context, RecorderService.class).setAction(ACTION_STOP);
        try {
            context.startService(intent);
        } catch (Exception ignored) { }
    }

    /**
     * A short buzz in place of a toast while recording: Android captures the
     * screen exactly as it is, so a toast shown then would sit in the video.
     */
    private void buzz(long[] pattern) {
        try {
            Vibrator vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
            if (vibrator == null || !vibrator.hasVibrator()) return;
            if (Build.VERSION.SDK_INT >= 26) {
                vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1));
            } else {
                vibrator.vibrate(pattern, -1);
            }
        } catch (Exception ignored) { }
    }

    /** A short toast, so starting and stopping is visible from inside a game. */
    private void toast(String text) {
        if (text == null) return;
        try {
            android.widget.Toast.makeText(getApplicationContext(), text,
                    android.widget.Toast.LENGTH_SHORT).show();
        } catch (Exception ignored) { }
    }

    private void refreshTile() {
        if (Build.VERSION.SDK_INT < 24) return;
        try {
            TileService.requestListeningState(this,
                    new ComponentName(this, QsTileService.class));
        } catch (Exception ignored) { }
    }
}
