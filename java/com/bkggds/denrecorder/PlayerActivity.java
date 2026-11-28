package com.bkggds.denrecorder;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.VideoView;

import java.util.Locale;

/**
 * Plays a recording right inside the app. Handing the file to the gallery app
 * proved unreliable (some galleries open the newest video instead of the one
 * that was tapped), and a player of our own also shows the video straight away
 * after it is recorded.
 *
 * The controls sit on soft gradients instead of solid bars, so a landscape
 * game keeps its whole picture and nothing covers the middle of the frame.
 */
public class PlayerActivity extends Activity {

    public static final String EXTRA_URI = "com.bkggds.denrecorder.extra.PLAYER_URI";
    public static final String EXTRA_TITLE = "com.bkggds.denrecorder.extra.PLAYER_TITLE";

    private static final int TEXT = Color.parseColor("#FAFAFA");
    private static final int MUTED = Color.parseColor("#C7C7CE");

    private static final int SEEK_STEP_MS = 10_000;

    private VideoView videoView;
    private LinearLayout topBar;
    private LinearLayout bottomBar;
    private ImageView playButton;
    private ImageView centerPlay;
    private SeekBar seekBar;
    private TextView timeView;
    private TextView seekHint;
    private TextView errorView;
    private android.view.GestureDetector gestures;

    private AudioManager audioManager;
    private AudioFocusRequest focusRequest;
    private boolean pausedByFocus;
    private final AudioManager.OnAudioFocusChangeListener focusListener = change -> {
        if (change == AudioManager.AUDIOFOCUS_LOSS
                || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            pausedByFocus = videoView != null && videoView.isPlaying();
            if (pausedByFocus) pause();
        } else if (change == AudioManager.AUDIOFOCUS_GAIN && pausedByFocus) {
            pausedByFocus = false;
            play();
        }
    };

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean dragging;
    private boolean prepared;
    private boolean controlsVisible = true;
    private boolean finished;
    private boolean isResumed;
    private boolean pendingPlay;

    private final Runnable hideControls = new Runnable() {
        @Override
        public void run() {
            if (videoView != null && videoView.isPlaying()) setControlsVisible(false);
        }
    };

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            updateProgress();
            handler.postDelayed(this, 500);
        }
    };

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleHelper.wrap(newBase));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        goFullscreen();

        Uri uri = null;
        try {
            uri = getIntent().getData();
        } catch (Exception ignored) { }
        if (uri == null) {
            finish();
            return;
        }
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        setContentView(root);

        videoView = new VideoView(this);
        root.addView(videoView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER));

        topBar = createTopBar();
        root.addView(topBar, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP));

        bottomBar = createBottomBar();
        root.addView(bottomBar, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));

        // A big play button in the middle, so the player reads at a glance when
        // it is paused.
        centerPlay = new ImageView(this);
        centerPlay.setImageResource(R.drawable.ic_play);
        centerPlay.setColorFilter(Color.WHITE);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(Color.parseColor("#73000000"));
        centerPlay.setBackground(circle);
        centerPlay.setPadding(dp(20), dp(20), dp(20), dp(20));
        centerPlay.setContentDescription(getString(R.string.player_play));
        centerPlay.setOnClickListener(v -> play());
        centerPlay.setVisibility(View.GONE);
        root.addView(centerPlay, new FrameLayout.LayoutParams(dp(76), dp(76), Gravity.CENTER));

        seekHint = new TextView(this);
        seekHint.setTextColor(TEXT);
        seekHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        seekHint.setGravity(Gravity.CENTER);
        seekHint.setBackground(round(Color.parseColor("#CC000000"), 16));
        seekHint.setPadding(dp(16), dp(10), dp(16), dp(10));
        seekHint.setAlpha(0f);
        root.addView(seekHint, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        errorView = new TextView(this);
        errorView.setText(getString(R.string.player_error));
        errorView.setTextColor(MUTED);
        errorView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        errorView.setGravity(Gravity.CENTER);
        errorView.setVisibility(View.GONE);
        root.addView(errorView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        // One tap shows or hides the controls, two taps seek by ten seconds
        // (left side back, right side on, in the middle play/pause).
        gestures = new android.view.GestureDetector(this,
                new android.view.GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public boolean onSingleTapConfirmed(android.view.MotionEvent event) {
                        setControlsVisible(!controlsVisible);
                        return true;
                    }

                    @Override
                    public boolean onDoubleTap(android.view.MotionEvent event) {
                        if (!prepared) return false;
                        int width = root.getWidth();
                        int position = videoView.getCurrentPosition();
                        int duration = Math.max(1, videoView.getDuration());
                        if (event.getX() < width / 3f) {
                            videoView.seekTo(Math.max(0, position - SEEK_STEP_MS));
                            showSeekHint(R.string.player_seek_back);
                        } else if (event.getX() > width * 2f / 3f) {
                            videoView.seekTo(Math.min(duration, position + SEEK_STEP_MS));
                            showSeekHint(R.string.player_seek_forward);
                        } else {
                            togglePlay();
                            return true;
                        }
                        finished = false;
                        updateProgress();
                        return true;
                    }
                });
        View.OnTouchListener gestureTouch = (v, event) -> {
            gestures.onTouchEvent(event);
            return true;
        };
        root.setOnTouchListener(gestureTouch);
        videoView.setOnTouchListener(gestureTouch);

        videoView.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
            @Override
            public void onPrepared(MediaPlayer player) {
                prepared = true;
                // Make playback use the media stream and the recording's own
                // audio level rather than inheriting a notification/call route.
                if (Build.VERSION.SDK_INT >= 21) {
                    player.setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                            .build());
                }
                player.setVolume(1f, 1f);
                seekBar.setMax(Math.max(1, videoView.getDuration()));
                play();
                updateProgress();
            }
        });
        videoView.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
            @Override
            public void onCompletion(MediaPlayer player) {
                finished = true;
                setPlayingIcon(false);
                setControlsVisible(true);
                updateProgress();
            }
        });
        videoView.setOnErrorListener(new MediaPlayer.OnErrorListener() {
            @Override
            public boolean onError(MediaPlayer player, int what, int extra) {
                prepared = false;
                errorView.setVisibility(View.VISIBLE);
                setPlayingIcon(false);
                setControlsVisible(true);
                return true;
            }
        });

        videoView.setVideoURI(uri);
        handler.post(tick);
    }

    /** Hides the bars while the video plays, so the picture is the whole screen. */
    private void goFullscreen() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    private LinearLayout createTopBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.VERTICAL);
        bar.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xE6000000, 0x00000000}));
        bar.setPadding(dp(8), dp(10), dp(8), dp(22));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        ImageView close = roundIcon(R.drawable.ic_close, getString(R.string.player_close));
        close.setOnClickListener(v -> finish());
        row.addView(close, new LinearLayout.LayoutParams(dp(38), dp(38)));

        TextView title = new TextView(this);
        title.setText(intentTitle());
        title.setTextColor(TEXT);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams titleParams =
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        titleParams.setMargins(dp(10), 0, dp(10), 0);
        row.addView(title, titleParams);

        // Turns the player itself, so a phone with rotation lock on still gets
        // the landscape view.
        ImageView rotate = roundIcon(R.drawable.ic_rotate, getString(R.string.player_rotate));
        rotate.setOnClickListener(v -> toggleOrientation());
        row.addView(rotate, new LinearLayout.LayoutParams(dp(38), dp(38)));

        bar.addView(row, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return bar;
    }

    private LinearLayout createBottomBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.VERTICAL);
        bar.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{0xE6000000, 0x00000000}));
        bar.setPadding(dp(16), dp(18), dp(16), dp(12));

        seekBar = new SeekBar(this);
        seekBar.setProgressTintList(android.content.res.ColorStateList.valueOf(Color.WHITE));
        seekBar.setThumbTintList(android.content.res.ColorStateList.valueOf(Color.WHITE));
        seekBar.setProgressBackgroundTintList(
                android.content.res.ColorStateList.valueOf(0x55FFFFFF));
        bar.addView(seekBar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar1, int progress, boolean fromUser) {
                if (fromUser) timeView.setText(length(progress) + " / " + length(bar1.getMax()));
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar1) {
                dragging = true;
                handler.removeCallbacks(hideControls);
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar1) {
                dragging = false;
                if (prepared) videoView.seekTo(bar1.getProgress());
                finished = false;
                handler.postDelayed(hideControls, 2500);
            }
        });

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        playButton = roundIcon(R.drawable.ic_play, getString(R.string.player_play));
        playButton.setOnClickListener(v -> togglePlay());
        row.addView(playButton, new LinearLayout.LayoutParams(dp(38), dp(38)));

        timeView = new TextView(this);
        timeView.setText("00:00 / 00:00");
        timeView.setTextColor(MUTED);
        timeView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        LinearLayout.LayoutParams timeParams =
                new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
        timeParams.setMargins(dp(12), 0, 0, 0);
        row.addView(timeView, timeParams);

        bar.addView(row, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return bar;
    }

    private ImageView roundIcon(int drawable, String description) {
        ImageView view = new ImageView(this);
        view.setImageResource(drawable);
        view.setColorFilter(TEXT);
        view.setBackground(round(Color.parseColor("#4D000000"), 19));
        view.setPadding(dp(8), dp(8), dp(8), dp(8));
        view.setContentDescription(description);
        return view;
    }

    /**
     * Flips between portrait and landscape. setRequestedOrientation is used on
     * purpose: it is not affected by the phone's own rotation lock.
     */
    private void toggleOrientation() {
        try {
            boolean landscape = getResources().getConfiguration().orientation
                    == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
            setRequestedOrientation(landscape
                    ? android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    : android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        } catch (Exception ignored) { }
    }

    /** A short "10s" pill so the jump is visible. */
    private void showSeekHint(int textRes) {
        if (seekHint == null) return;
        seekHint.animate().cancel();
        seekHint.setText(getString(textRes));
        seekHint.setAlpha(1f);
        seekHint.animate().alpha(0f).setStartDelay(500).setDuration(400).start();
    }

    private String intentTitle() {
        try {
            String title = getIntent().getStringExtra(EXTRA_TITLE);
            if (title != null && !title.isEmpty()) return title;
        } catch (Exception ignored) { }
        return getString(R.string.nav_recordings);
    }

    // ---------- Playback ----------

    private void play() {
        if (!prepared || videoView == null) return;
        // The ROM mutes an app that starts playing while it is not the app on
        // screen, and that mute outlasts the app coming back to the front, so
        // playback only ever starts from the screen the user is looking at.
        if (!isResumed || !hasWindowFocus()) {
            pendingPlay = true;
            return;
        }
        pendingPlay = false;
        if (finished) {
            finished = false;
            videoView.seekTo(0);
        }
        if (!requestAudioFocus()) {
            // The system refused the focus: it will keep this app silent, and
            // the warning above says so.
        }
        videoView.start();
        setPlayingIcon(true);
        handler.removeCallbacks(hideControls);
        handler.postDelayed(hideControls, 3000);
    }

    private void pause() {
        if (videoView == null) return;
        videoView.pause();
        setPlayingIcon(false);
        setControlsVisible(true);
    }

    private void togglePlay() {
        if (!prepared) return;
        if (videoView.isPlaying()) pause(); else play();
    }

    private boolean requestAudioFocus() {
        try {
            if (audioManager == null) return false;
            AudioAttributes attributes = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build();
            if (Build.VERSION.SDK_INT >= 26) {
                if (focusRequest == null) {
                    focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                            .setAudioAttributes(attributes)
                            .setOnAudioFocusChangeListener(focusListener)
                            .build();
                }
                return audioManager.requestAudioFocus(focusRequest)
                        == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
            }
            return audioManager.requestAudioFocus(focusListener,
                    AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
                    == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        } catch (Exception e) {
            return false;
        }
    }

    private void abandonAudioFocus() {
        try {
            if (audioManager == null) return;
            if (Build.VERSION.SDK_INT >= 26) {
                if (focusRequest != null) audioManager.abandonAudioFocusRequest(focusRequest);
            } else {
                audioManager.abandonAudioFocus(focusListener);
            }
        } catch (Exception ignored) { }
    }

    private void setPlayingIcon(boolean playing) {
        if (playButton != null) {
            playButton.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);
            playButton.setContentDescription(getString(
                    playing ? R.string.player_pause : R.string.player_play));
        }
        if (centerPlay != null) {
            centerPlay.setVisibility(playing || !prepared ? View.GONE : View.VISIBLE);
        }
    }

    private void setControlsVisible(boolean visible) {
        controlsVisible = visible;
        handler.removeCallbacks(hideControls);
        if (topBar != null) animate(topBar, visible);
        if (bottomBar != null) animate(bottomBar, visible);
        // A paused video always keeps its big play button on screen.
        if (centerPlay != null) {
            centerPlay.setVisibility(prepared
                    && (visible || (videoView != null && !videoView.isPlaying()))
                    ? View.VISIBLE : View.GONE);
        }
    }

    private void animate(View view, boolean visible) {
        view.animate().cancel();
        view.animate()
                .alpha(visible ? 1f : 0f)
                .setDuration(160)
                .start();
        view.setClickable(visible);
    }

    private void updateProgress() {
        if (videoView == null || !prepared) return;
        int position = videoView.getCurrentPosition();
        int duration = videoView.getDuration();
        if (duration > 0 && seekBar.getMax() != duration) seekBar.setMax(duration);
        if (!dragging) seekBar.setProgress(Math.max(0, Math.min(position, seekBar.getMax())));
        timeView.setText(length(position) + " / " + length(Math.max(0, duration)));
        setPlayingIcon(videoView.isPlaying());
    }

    private static String length(int ms) {
        long total = Math.max(0, ms) / 1000L;
        long hours = total / 3600;
        long minutes = (total % 3600) / 60;
        long seconds = total % 60;
        if (hours > 0) {
            return String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds);
        }
        return String.format(Locale.US, "%02d:%02d", minutes, seconds);
    }

    private GradientDrawable round(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        return drawable;
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (!hasFocus) return;
        goFullscreen();
        if (pendingPlay) {
            pendingPlay = false;
            play();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        isResumed = true;
        if (pendingPlay) {
            pendingPlay = false;
            play();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        isResumed = false;
        if (videoView != null) {
            try {
                if (videoView.isPlaying()) videoView.pause();
            } catch (Exception ignored) { }
        }
        abandonAudioFocus();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        abandonAudioFocus();
        if (videoView != null) {
            try {
                videoView.stopPlayback();
            } catch (Exception ignored) { }
        }
        super.onDestroy();
    }

    /** Opens a recording in the player. */
    public static void open(Context context, Uri uri, String title) {
        Intent intent = new Intent(context, PlayerActivity.class)
                .setData(uri)
                .putExtra(EXTRA_TITLE, title)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        context.startActivity(intent);
    }
}
