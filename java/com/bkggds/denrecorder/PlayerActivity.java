package com.bkggds.denrecorder;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.net.Uri;
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
 */
public class PlayerActivity extends Activity {

    public static final String EXTRA_URI = "com.bkggds.denrecorder.extra.PLAYER_URI";
    public static final String EXTRA_TITLE = "com.bkggds.denrecorder.extra.PLAYER_TITLE";

    private static final int TEXT = Color.parseColor("#FAFAFA");
    private static final int MUTED = Color.parseColor("#A1A1AA");
    private static final int PANEL = Color.parseColor("#E6111113");

    private VideoView videoView;
    private LinearLayout topBar;
    private LinearLayout bottomBar;
    private ImageView playButton;
    private SeekBar seekBar;
    private TextView timeView;
    private TextView errorView;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean dragging;
    private boolean prepared;
    private boolean controlsVisible = true;
    private boolean finished;

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

        Uri uri = null;
        try {
            uri = getIntent().getData();
        } catch (Exception ignored) { }
        if (uri == null) {
            finish();
            return;
        }

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        setContentView(root);

        videoView = new VideoView(this);
        root.addView(videoView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER));

        topBar = createTopBar();
        FrameLayout.LayoutParams topParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP);
        topParams.setMargins(dp(10), dp(10), dp(10), 0);
        root.addView(topBar, topParams);

        bottomBar = createBottomBar();
        FrameLayout.LayoutParams bottomParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        bottomParams.setMargins(dp(10), 0, dp(10), dp(10));
        root.addView(bottomBar, bottomParams);

        errorView = new TextView(this);
        errorView.setText(getString(R.string.player_error));
        errorView.setTextColor(MUTED);
        errorView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        errorView.setGravity(Gravity.CENTER);
        errorView.setVisibility(View.GONE);
        root.addView(errorView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        View.OnClickListener toggle = v -> setControlsVisible(!controlsVisible);
        root.setOnClickListener(toggle);
        videoView.setOnClickListener(toggle);

        videoView.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
            @Override
            public void onPrepared(MediaPlayer player) {
                prepared = true;
                seekBar.setMax(Math.max(1, videoView.getDuration()));
                videoView.start();
                updateProgress();
                handler.postDelayed(hideControls, 3000);
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

    private LinearLayout createTopBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackground(round(PANEL, 14));
        bar.setPadding(dp(12), dp(10), dp(12), dp(10));
        bar.setClickable(true);

        ImageView close = new ImageView(this);
        close.setImageResource(R.drawable.ic_close);
        close.setColorFilter(TEXT);
        close.setBackground(round(Color.parseColor("#27272A"), 16));
        close.setPadding(dp(6), dp(6), dp(6), dp(6));
        close.setContentDescription(getString(R.string.player_close));
        close.setOnClickListener(v -> finish());
        bar.addView(close, new LinearLayout.LayoutParams(dp(30), dp(30)));

        TextView title = new TextView(this);
        title.setText(intentTitle());
        title.setTextColor(TEXT);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        title.setSingleLine(true);
        LinearLayout.LayoutParams titleParams =
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        titleParams.setMargins(dp(10), 0, 0, 0);
        bar.addView(title, titleParams);
        return bar;
    }

    private LinearLayout createBottomBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackground(round(PANEL, 14));
        bar.setPadding(dp(10), dp(6), dp(12), dp(6));
        bar.setClickable(true);

        playButton = new ImageView(this);
        playButton.setImageResource(R.drawable.ic_play);
        playButton.setColorFilter(TEXT);
        playButton.setPadding(dp(6), dp(6), dp(6), dp(6));
        playButton.setContentDescription(getString(R.string.player_play));
        playButton.setOnClickListener(v -> togglePlay());
        bar.addView(playButton, new LinearLayout.LayoutParams(dp(32), dp(32)));

        seekBar = new SeekBar(this);
        LinearLayout.LayoutParams seekParams =
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        seekParams.setMargins(dp(6), 0, dp(6), 0);
        bar.addView(seekBar, seekParams);
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar1, int progress, boolean fromUser) {
                if (fromUser) timeView.setText(length(progress) + " / " + length(bar1.getMax()));
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar1) {
                dragging = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar1) {
                dragging = false;
                if (prepared) videoView.seekTo(bar1.getProgress());
                finished = false;
            }
        });

        timeView = new TextView(this);
        timeView.setText("00:00 / 00:00");
        timeView.setTextColor(MUTED);
        timeView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        bar.addView(timeView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        return bar;
    }

    private String intentTitle() {
        try {
            String title = getIntent().getStringExtra(EXTRA_TITLE);
            if (title != null && !title.isEmpty()) return title;
        } catch (Exception ignored) { }
        return getString(R.string.nav_recordings);
    }

    private void togglePlay() {
        if (!prepared) return;
        if (videoView.isPlaying()) {
            videoView.pause();
            setPlayingIcon(false);
            setControlsVisible(true);
            return;
        }
        if (finished) {
            finished = false;
            videoView.seekTo(0);
        }
        videoView.start();
        setPlayingIcon(true);
        handler.removeCallbacks(hideControls);
        handler.postDelayed(hideControls, 3000);
    }

    private void setPlayingIcon(boolean playing) {
        if (playButton == null) return;
        playButton.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);
        playButton.setContentDescription(getString(
                playing ? R.string.player_pause : R.string.player_play));
    }

    private void setControlsVisible(boolean visible) {
        controlsVisible = visible;
        handler.removeCallbacks(hideControls);
        if (topBar != null) animate(topBar, visible);
        if (bottomBar != null) animate(bottomBar, visible);
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
    protected void onPause() {
        super.onPause();
        if (videoView != null) {
            try {
                if (videoView.isPlaying()) videoView.pause();
            } catch (Exception ignored) { }
        }
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
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
