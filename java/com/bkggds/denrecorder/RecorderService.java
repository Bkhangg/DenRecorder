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
import android.service.quicksettings.TileService;
import android.util.Log;

public class RecorderService extends Service {

    @Override
    protected void attachBaseContext(android.content.Context newBase) {
        super.attachBaseContext(LocaleHelper.wrap(newBase));
    }

    private static final String TAG = "RecorderService";

    public static final String ACTION_START = "com.bkggds.denrecorder.action.START";
    public static final String ACTION_STOP = "com.bkggds.denrecorder.action.STOP";

    public static final String EXTRA_RESULT_CODE = "resultCode";
    public static final String EXTRA_DATA = "data";
    public static final String EXTRA_AUDIO = "audio";

    public static volatile boolean isRecording;
    public static volatile long startedAt;
    public static volatile Uri lastUri;
    public static volatile String lastError;
    public static volatile String lastSummary;

    private static final String CHANNEL_ID = "recording";
    private static final int NOTIF_ID = 4711;

    /** Do not start a recording with less free space than this. */
    private static final long MIN_FREE_BYTES = 400L * 1024 * 1024;
    /** Stop a long recording before the card fills up completely. */
    private static final long LOW_SPACE_BYTES = 150L * 1024 * 1024;

    private ScreenRecorder recorder;
    private MediaProjection projection;
    private Handler handler;
    private boolean finishing;
    private volatile boolean selfStop;

    /**
     * Long recordings (an hour and more) must not need the app to do anything
     * every second: the notification counts time by itself (chronometer), and
     * this ticker only checks that storage is not running out.
     */
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
        handler = new Handler(Looper.getMainLooper());
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopRecording(null);
            return START_NOT_STICKY;
        }
        if (ACTION_START.equals(action)) {
            begin(intent);
        }
        return START_NOT_STICKY;
    }

    private void begin(Intent intent) {
        int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED);
        Intent data;
        if (Build.VERSION.SDK_INT >= 33) {
            data = intent.getParcelableExtra(EXTRA_DATA, Intent.class);
        } else {
            data = intent.getParcelableExtra(EXTRA_DATA);
        }
        int audio = intent.getIntExtra(EXTRA_AUDIO, ScreenRecorder.AUDIO_NONE);

        int type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION;
        if (audio == ScreenRecorder.AUDIO_MIC) {
            type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
        }
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, buildNotification(getString(R.string.notification_starting)), type);
        } else {
            startForeground(NOTIF_ID, buildNotification(getString(R.string.notification_starting)));
        }

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
                Log.i(TAG, "Projection stopped by system");
                stopRecording("Screen capture was stopped");
            }
        }, handler);

        lastError = null;
        lastUri = null;
        startedAt = System.currentTimeMillis();
        isRecording = true;
        handler.post(ticker);

        try {
            android.content.SharedPreferences prefs =
                    getSharedPreferences("settings", MODE_PRIVATE);
            int resWidth = prefs.getInt("resWidth", 0);
            int fps = prefs.getInt("fps", 60);
            if (prefs.getBoolean("perfMode", true) && fps > 60) {
                // Keep a game next to the recorder smooth: 60 fps is enough.
                fps = 60;
            }
            int bitrate = prefs.getInt("bitrateMbps", 32) * 1_000_000;
            String codec = prefs.getString("codec", "avc");
            recorder = new ScreenRecorder(getApplicationContext(), projection, audio,
                    resWidth, fps, bitrate,
                    "hevc".equals(codec) ? ScreenRecorder.CODEC_HEVC : ScreenRecorder.CODEC_AVC);
            recorder.start();
            updateNotification();
            refreshTile();
            toast(getString(R.string.toast_recording_started));
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

        final ScreenRecorder active = recorder;
        recorder = null;
        final String failReason = reason;

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    if (active != null) active.stop();
                } catch (Exception e) {
                    Log.w(TAG, "Error while stopping", e);
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

                final Uri savedUri = uri;
                final String savedError = failReason != null ? failReason : lastError;
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        finishing = false;
                        refreshTile();
                        if (savedError != null) {
                            toast(savedError);
                        } else if (savedUri != null) {
                            toast(getString(R.string.toast_recording_saved));
                        }
                        try { stopForeground(true); } catch (Exception ignored) { }
                        stopSelf();
                    }
                });
            }
        }, "rec-stop").start();
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(ticker);
        isRecording = false;
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
        if (Build.VERSION.SDK_INT >= 23) {
            stopAction = new Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
                    getString(R.string.notification_stop), stopIntent).build();
        } else {
            stopAction = new Notification.Action.Builder(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    getString(R.string.notification_stop), stopIntent).build();
        }
        builder.addAction(stopAction);
        return builder.build();
    }

    private void updateNotification() {
        if (!isRecording || recorder == null) return;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        try {
            nm.notify(NOTIF_ID, buildNotification(recorder.describeSettings()));
        } catch (Exception ignored) { }
    }

    public static void requestStop(android.content.Context context) {
        Intent intent = new Intent(context, RecorderService.class).setAction(ACTION_STOP);
        try {
            context.startService(intent);
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
