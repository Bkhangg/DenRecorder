package com.bkggds.denrecorder;

import android.Manifest;
import android.app.Activity;
import android.app.Dialog;
import android.app.StatusBarManager;
import android.animation.ValueAnimator;
import android.content.ComponentName;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Icon;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaMetadataRetriever;
import android.media.projection.MediaProjectionConfig;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.MediaStore;
import android.provider.Settings;
import android.service.quicksettings.TileService;
import android.util.DisplayMetrics;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    public static final String EXTRA_AUTO_START =
            "com.bkggds.denrecorder.extra.AUTO_START";

    private static final int REQ_PROJECTION = 1001;
    private static final int REQ_PERMISSIONS = 1002;
    private static final int REQ_WRITE_SETTINGS = 1003;

    // Shadcn-style palette (zinc scale).
    private static final int BG = Color.parseColor("#09090B");
    private static final int CARD = Color.parseColor("#111113");
    private static final int BORDER = Color.parseColor("#27272A");
    private static final int TEXT = Color.parseColor("#FAFAFA");
    private static final int MUTED = Color.parseColor("#A1A1AA");
    private static final int FAINT = Color.parseColor("#71717A");
    private static final int PRIMARY = Color.parseColor("#FAFAFA");
    private static final int PRIMARY_FG = Color.parseColor("#09090B");
    private static final int RED = Color.parseColor("#EF4444");
    private static final int GREEN = Color.parseColor("#22C55E");
    private static final int NAV = Color.parseColor("#18181B");
    private static final int NAV_ACTIVE = Color.parseColor("#27272A");

    private static final int MATCH = LinearLayout.LayoutParams.MATCH_PARENT;
    private static final int WRAP = LinearLayout.LayoutParams.WRAP_CONTENT;

    private SharedPreferences prefs;
    private Handler handler;

    private int screen = 0; // 0 = record, 1 = settings, 2 = recordings
    private TextView screenTitle;
    private ImageView backButton;
    private ImageView settingsButton;
    private ScrollView homeScroll;
    private ScrollView settingsScroll;
    private ScrollView recordingsScroll;

    private TextView heroTitle;
    private TextView heroBody;
    private TextView statusView;
    private Button mainButton;

    private TextView resolutionValueView;
    private TextView bitrateValueView;
    private TextView fpsValueView;
    private TextView codecValueView;
    private TextView audioValueView;
    private TextView batteryValueView;
    private TextView tileValueView;
    private TextView languageValueView;
    private TextView storageValueView;
    private TextView orientationValueView;
    private TextView performanceValueView;
    private View recordingDot;
    private ValueAnimator dotAnimator;

    private LinearLayout recordingsList;
    private TextView recordingsSummary;
    private TextView recordingsHint;
    private TextView recentTab;
    private TextView allTab;
    private boolean showAllRecordings;

    private LinearLayout navRecord;
    private LinearLayout navRecordings;
    private LinearLayout navSettings;
    private ImageView navRecordIcon;
    private ImageView navRecordingsIcon;
    private ImageView navSettingsIcon;
    private TextView navRecordLabel;
    private TextView navRecordingsLabel;
    private TextView navSettingsLabel;

    private Object backCallback;

    private int audioSource = ScreenRecorder.AUDIO_INTERNAL;
    private int resWidth;          // 0 = native
    private int videoFps = 60;
    private int bitrateMbps = 32;
    private String codec = "avc";
    private String language = "en";
    private String orientation = "auto";
    private boolean perfMode = true;
    private boolean pendingStart;
    private boolean sawRecording;
    private boolean projectionRequested;
    private boolean uiRecording;
    private boolean dialogShown;
    private long stopDetectedAt;

    private final ExecutorService thumbPool = Executors.newFixedThreadPool(2);
    private final LruCache<String, Bitmap> thumbCache = new LruCache<String, Bitmap>(48);

    /** The folder recordings are saved in, relative to shared storage. */
    private String saveFolder() {
        String folder = prefs.getString("saveFolder", "Movies/Screen Recorder");
        if (folder == null) folder = "Movies/Screen Recorder";
        folder = folder.replaceAll("^/+", "").replaceAll("/+$", "");
        return folder.isEmpty() ? "Movies/Screen Recorder" : folder;
    }

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            updateUi();
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleHelper.wrap(newBase));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);

        prefs = getSharedPreferences(LocaleHelper.PREFS, MODE_PRIVATE);
        audioSource = prefs.getInt("audio", ScreenRecorder.AUDIO_INTERNAL);
        resWidth = prefs.getInt("resWidth", 0);
        videoFps = prefs.getInt("fps", 60);
        bitrateMbps = prefs.getInt("bitrateMbps", 32);
        codec = prefs.getString("codec", "avc");
        language = prefs.getString(LocaleHelper.KEY, "en");
        orientation = prefs.getString("orientation", "auto");
        perfMode = prefs.getBoolean("perfMode", true);
        handler = new Handler(Looper.getMainLooper());

        buildUi();
        boolean openSettings = prefs.getBoolean("openSettingsNext", false);
        if (openSettings) prefs.edit().putBoolean("openSettingsNext", false).apply();
        showScreen(openSettings ? 1 : 0);
        updateQualityValues();
        updateAudioValue();
        updateBatteryValue();
        updateTileValue();
        updateLanguageValue();
        handleAutoStart(getIntent());
        maybeShowSetup();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleAutoStart(intent);
    }

    /** Started from the Quick Settings tile: go straight to the capture prompt. */
    private void handleAutoStart(Intent intent) {
        if (intent == null || !intent.getBooleanExtra(EXTRA_AUTO_START, false)) return;
        intent.removeExtra(EXTRA_AUTO_START);
        if (RecorderService.isRecording) return;
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!isFinishing() && !projectionRequested && !pendingStart) {
                    beginStart();
                }
            }
        }, 300);
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.removeCallbacks(poll);
        handler.post(poll);
        updateBatteryValue();
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(poll);
    }

    @Override
    protected void onDestroy() {
        if (dotAnimator != null) {
            dotAnimator.cancel();
            dotAnimator = null;
        }
        try { thumbPool.shutdownNow(); } catch (Exception ignored) { }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (screen != 0) {
            showScreen(0);
            return;
        }
        super.onBackPressed();
    }

    /** Back goes to the record screen first, it only leaves the app from there. */
    @SuppressWarnings("NewApi")
    private void updateBackCallback() {
        if (Build.VERSION.SDK_INT < 33) return;
        try {
            android.window.OnBackInvokedDispatcher dispatcher = getOnBackInvokedDispatcher();
            if (backCallback != null) {
                dispatcher.unregisterOnBackInvokedCallback(
                        (android.window.OnBackInvokedCallback) backCallback);
                backCallback = null;
            }
            if (screen != 0) {
                android.window.OnBackInvokedCallback callback = this::showRecordScreen;
                dispatcher.registerOnBackInvokedCallback(
                        android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback);
                backCallback = callback;
            }
        } catch (Exception ignored) { }
    }

    private void showRecordScreen() {
        showScreen(0);
    }

    // ---------- UI ----------

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(BG);
        setContentView(root);

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        root.addView(column, new FrameLayout.LayoutParams(MATCH, MATCH));

        // Top bar
        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(dp(10), dp(8) + barHeight("status_bar_height"), dp(10), dp(6));
        column.addView(topBar, new LinearLayout.LayoutParams(MATCH, WRAP));

        backButton = iconButton(R.drawable.ic_back, FAINT);
        backButton.setOnClickListener(v -> showScreen(0));
        topBar.addView(backButton, new LinearLayout.LayoutParams(dp(40), dp(40)));
        backButton.setVisibility(View.GONE);

        screenTitle = label(getString(R.string.app_name), 21, TEXT, true);
        LinearLayout.LayoutParams titleParams =
                new LinearLayout.LayoutParams(0, WRAP, 1f);
        titleParams.setMargins(dp(8), 0, 0, 0);
        topBar.addView(screenTitle, titleParams);

        settingsButton = iconButton(R.drawable.ic_settings, MUTED);
        settingsButton.setOnClickListener(v -> showScreen(1));
        topBar.addView(settingsButton, new LinearLayout.LayoutParams(dp(40), dp(40)));

        // Screens
        FrameLayout content = new FrameLayout(this);
        column.addView(content, new LinearLayout.LayoutParams(MATCH, 0, 1f));

        homeScroll = new ScrollView(this);
        homeScroll.setFillViewport(true);
        homeScroll.setClipToPadding(false);
        homeScroll.setPadding(dp(20), dp(6), dp(20), dp(120));
        content.addView(homeScroll, new FrameLayout.LayoutParams(MATCH, MATCH));

        settingsScroll = new ScrollView(this);
        settingsScroll.setFillViewport(true);
        settingsScroll.setClipToPadding(false);
        settingsScroll.setPadding(dp(20), dp(6), dp(20), dp(120));
        content.addView(settingsScroll, new FrameLayout.LayoutParams(MATCH, MATCH));
        settingsScroll.setVisibility(View.GONE);

        recordingsScroll = new ScrollView(this);
        recordingsScroll.setFillViewport(true);
        recordingsScroll.setClipToPadding(false);
        recordingsScroll.setPadding(dp(20), dp(6), dp(20), dp(120));
        content.addView(recordingsScroll, new FrameLayout.LayoutParams(MATCH, MATCH));
        recordingsScroll.setVisibility(View.GONE);

        buildHome();
        buildSettings();
        buildRecordings();

        // Floating bottom navigation bar
        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setBackground(round(NAV, 30, BORDER));
        nav.setPadding(dp(6), dp(6), dp(6), dp(6));

        navRecord = navItem(R.drawable.ic_record, getString(R.string.nav_record));
        navRecordIcon = (ImageView) navRecord.getChildAt(0);
        navRecordLabel = (TextView) navRecord.getChildAt(1);
        navRecord.setOnClickListener(v -> showScreen(0));
        nav.addView(navRecord, new LinearLayout.LayoutParams(0, dp(48), 1f));

        navRecordings = navItem(R.drawable.ic_video, getString(R.string.nav_recordings));
        navRecordingsIcon = (ImageView) navRecordings.getChildAt(0);
        navRecordingsLabel = (TextView) navRecordings.getChildAt(1);
        navRecordings.setOnClickListener(v -> showScreen(2));
        nav.addView(navRecordings, new LinearLayout.LayoutParams(0, dp(48), 1f));

        navSettings = navItem(R.drawable.ic_settings, getString(R.string.nav_settings));
        navSettingsIcon = (ImageView) navSettings.getChildAt(0);
        navSettingsLabel = (TextView) navSettings.getChildAt(1);
        navSettings.setOnClickListener(v -> showScreen(1));
        nav.addView(navSettings, new LinearLayout.LayoutParams(0, dp(48), 1f));

        FrameLayout.LayoutParams navParams =
                new FrameLayout.LayoutParams(dp(330), WRAP, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        navParams.setMargins(0, 0, 0, dp(18) + barHeight("navigation_bar_height"));
        root.addView(nav, navParams);
    }

    private void buildHome() {
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        homeScroll.addView(list, new ScrollView.LayoutParams(MATCH, WRAP));

        LinearLayout hero = card();
        hero.setPadding(dp(20), dp(20), dp(20), dp(20));
        list.addView(hero, lp(MATCH, WRAP, 0, 8, 0, 0));

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        hero.addView(titleRow, lp(MATCH, WRAP, 0, 0, 0, 0));

        recordingDot = new View(this);
        recordingDot.setBackground(round(RED, 6, 0));
        recordingDot.setVisibility(View.GONE);
        LinearLayout.LayoutParams dotParams = new LinearLayout.LayoutParams(dp(10), dp(10));
        dotParams.setMargins(0, dp(2), dp(12), 0);
        titleRow.addView(recordingDot, dotParams);

        heroTitle = label(getString(R.string.hero_ready_title), 24, TEXT, true);
        titleRow.addView(heroTitle, new LinearLayout.LayoutParams(WRAP, WRAP));

        heroBody = label("", 13, MUTED, false);
        heroBody.setLineSpacing(dp(3), 1f);
        hero.addView(heroBody, lp(MATCH, WRAP, 0, 8, 0, 0));

        statusView = label(getString(R.string.status_ready, saveFolder()), 13, MUTED, false);
        statusView.setLineSpacing(dp(2), 1f);
        hero.addView(statusView, lp(MATCH, WRAP, 0, 14, 0, 0));

        mainButton = new Button(this);
        mainButton.setAllCaps(false);
        mainButton.setText(getString(R.string.start_recording));
        mainButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        mainButton.setTextColor(PRIMARY_FG);
        mainButton.setTypeface(Typeface.DEFAULT_BOLD);
        mainButton.setStateListAnimator(null);
        styleButton(mainButton, PRIMARY);
        mainButton.setOnClickListener(v -> onMainButton());
        pressEffect(mainButton);
        list.addView(mainButton, lp(MATCH, dp(52), 0, 16, 0, 0));

        TextView hint = label(getString(R.string.hint_home), 12, FAINT, false);
        hint.setLineSpacing(dp(3), 1f);
        list.addView(hint, lp(MATCH, WRAP, 0, 18, 0, 0));
    }

    private void buildSettings() {
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        settingsScroll.addView(list, new ScrollView.LayoutParams(MATCH, WRAP));

        list.addView(sectionLabel(R.string.section_capture), lp(MATCH, WRAP, 0, 10, 0, 0));
        LinearLayout qualityCard = card();
        list.addView(qualityCard, lp(MATCH, WRAP, 0, 8, 0, 0));
        resolutionValueView = addRow(qualityCard, R.drawable.ic_screen, R.string.row_resolution,
                "", true, this::chooseResolution);
        divider(qualityCard);
        bitrateValueView = addRow(qualityCard, R.drawable.ic_video, R.string.row_quality,
                "", true, this::chooseBitrate);
        divider(qualityCard);
        fpsValueView = addRow(qualityCard, R.drawable.ic_record, R.string.row_fps,
                "", true, this::chooseFps);
        divider(qualityCard);
        performanceValueView = addRow(qualityCard, R.drawable.ic_chip, R.string.row_performance,
                "", true, this::togglePerformance);
        divider(qualityCard);
        codecValueView = addRow(qualityCard, R.drawable.ic_chip, R.string.row_codec,
                "", true, this::chooseCodec);

        list.addView(sectionLabel(R.string.section_audio), lp(MATCH, WRAP, 0, 22, 0, 0));
        LinearLayout audioCard = card();
        list.addView(audioCard, lp(MATCH, WRAP, 0, 8, 0, 0));
        audioValueView = addRow(audioCard, R.drawable.ic_mic, R.string.row_audio,
                "", true, this::chooseAudioSource);

        list.addView(sectionLabel(R.string.section_general), lp(MATCH, WRAP, 0, 22, 0, 0));
        LinearLayout generalCard = card();
        list.addView(generalCard, lp(MATCH, WRAP, 0, 8, 0, 0));
        batteryValueView = addRow(generalCard, R.drawable.ic_battery, R.string.row_battery,
                "", true, this::requestBatteryExemption);
        divider(generalCard);
        tileValueView = addRow(generalCard, R.drawable.ic_tile, R.string.row_tile,
                "", true, this::requestTile);
        divider(generalCard);
        languageValueView = addRow(generalCard, R.drawable.ic_language, R.string.row_language,
                "", true, this::chooseLanguage);
        divider(generalCard);
        storageValueView = addRow(generalCard, R.drawable.ic_folder, R.string.row_storage,
                saveFolder(), true, this::chooseSaveFolder);
        divider(generalCard);
        orientationValueView = addRow(generalCard, R.drawable.ic_screen, R.string.row_orientation,
                "", true, this::chooseOrientation);
        divider(generalCard);
        addHint(generalCard, R.string.hint_orientation);

        list.addView(sectionLabel(R.string.section_about), lp(MATCH, WRAP, 0, 22, 0, 0));
        LinearLayout aboutCard = card();
        list.addView(aboutCard, lp(MATCH, WRAP, 0, 8, 0, 0));
        addRow(aboutCard, R.drawable.ic_info, R.string.row_version, versionName(), false, null);
        divider(aboutCard);
        addRow(aboutCard, R.drawable.ic_info, R.string.row_credit,
                getString(R.string.value_credit), false, null);
    }

    private void buildRecordings() {
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        recordingsScroll.addView(list, new ScrollView.LayoutParams(MATCH, WRAP));

        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.setBackground(round(CARD, 12, BORDER));
        tabs.setPadding(dp(4), dp(4), dp(4), dp(4));
        list.addView(tabs, lp(MATCH, WRAP, 0, 10, 0, 0));

        recentTab = tabLabel(getString(R.string.recordings_recent));
        recentTab.setOnClickListener(v -> {
            showAllRecordings = false;
            loadRecordings();
        });
        tabs.addView(recentTab, new LinearLayout.LayoutParams(0, dp(40), 1f));

        allTab = tabLabel(getString(R.string.recordings_all));
        allTab.setOnClickListener(v -> {
            showAllRecordings = true;
            loadRecordings();
        });
        tabs.addView(allTab, new LinearLayout.LayoutParams(0, dp(40), 1f));

        recordingsSummary = label("", 12, FAINT, false);
        list.addView(recordingsSummary, lp(MATCH, WRAP, 0, 14, 0, 0));

        recordingsHint = label("", 12, FAINT, false);
        recordingsHint.setLineSpacing(dp(3), 1f);
        list.addView(recordingsHint, lp(MATCH, WRAP, 0, 4, 0, 0));

        recordingsList = new LinearLayout(this);
        recordingsList.setOrientation(LinearLayout.VERTICAL);
        list.addView(recordingsList, lp(MATCH, WRAP, 0, 10, 0, 0));
    }

    private TextView tabLabel(String text) {
        TextView view = label(text, 13, MUTED, true);
        view.setGravity(Gravity.CENTER);
        view.setClickable(true);
        return view;
    }

    private void styleTabs() {
        if (recentTab == null || allTab == null) return;
        recentTab.setBackground(!showAllRecordings ? round(NAV_ACTIVE, 10, 0) : null);
        recentTab.setTextColor(!showAllRecordings ? TEXT : FAINT);
        allTab.setBackground(showAllRecordings ? round(NAV_ACTIVE, 10, 0) : null);
        allTab.setTextColor(showAllRecordings ? TEXT : FAINT);
    }

    private void loadRecordings() {
        if (recordingsList == null) return;
        styleTabs();
        recordingsList.removeAllViews();
        String folder = saveFolder();
        if (recordingsHint != null) {
            recordingsHint.setText(getString(R.string.hint_recordings, folder));
        }

        List<Recording> items = new ArrayList<>();
        Cursor cursor = null;
        try {
            String[] projection = new String[]{
                    MediaStore.Video.Media._ID,
                    MediaStore.Video.Media.DISPLAY_NAME,
                    MediaStore.Video.Media.DURATION,
                    MediaStore.Video.Media.SIZE,
                    MediaStore.Video.Media.DATE_ADDED,
                    MediaStore.Video.Media.WIDTH,
                    MediaStore.Video.Media.HEIGHT
            };
            String selection;
            String[] args;
            if (Build.VERSION.SDK_INT >= 29) {
                selection = MediaStore.Video.Media.RELATIVE_PATH + "=? OR "
                        + MediaStore.Video.Media.RELATIVE_PATH + "=?";
                args = new String[]{folder, folder + "/"};
            } else {
                // Android 9 and older: find the videos by their path on the card.
                java.io.File dir = ScreenRecorder.publicFolder(folder);
                String prefix = (dir == null ? folder : dir.getAbsolutePath()) + "/";
                selection = MediaStore.Video.Media.DATA + " LIKE ?";
                args = new String[]{prefix + "%"};
            }
            cursor = getContentResolver().query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    projection, selection, args,
                    MediaStore.Video.Media.DATE_ADDED + " DESC");
            while (cursor != null && cursor.moveToNext()) {
                long id = cursor.getLong(0);
                String name = cursor.getString(1);
                long duration = cursor.getLong(2);
                long size = cursor.getLong(3);
                long added = cursor.getLong(4);
                int width = cursor.getInt(5);
                int height = cursor.getInt(6);
                Uri uri = ContentUris.withAppendedId(
                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id);
                items.add(new Recording(uri, name, added, duration, size, width, height));
                if (items.size() >= 500) break;
            }
        } catch (Exception ignored) {
        } finally {
            try { if (cursor != null) cursor.close(); } catch (Exception ignored) { }
        }

        long totalSize = 0;
        for (Recording item : items) totalSize += item.size;
        recordingsSummary.setText(getString(R.string.recordings_count,
                items.size(), formatSize(totalSize)));

        int shown = showAllRecordings ? items.size() : Math.min(10, items.size());
        if (shown == 0) {
            TextView empty = label(getString(R.string.recordings_empty), 13, FAINT, false);
            empty.setLineSpacing(dp(3), 1f);
            recordingsList.addView(empty, lp(MATCH, WRAP, 0, 12, 0, 0));
            return;
        }

        LinearLayout card = card();
        recordingsList.addView(card, lp(MATCH, WRAP, 0, 0, 0, 0));
        for (int i = 0; i < shown; i++) {
            if (i > 0) divider(card);
            addRecordingRow(card, items.get(i));
        }
    }

    private void addRecordingRow(LinearLayout parent, final Recording item) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(12), 0, dp(12));
        row.setClickable(true);
        row.setFocusable(true);

        final ImageView thumb = new ImageView(this);
        thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
        thumb.setBackground(round(NAV, 10, BORDER));
        LinearLayout.LayoutParams thumbParams = new LinearLayout.LayoutParams(dp(58), dp(58));
        thumbParams.setMargins(0, 0, dp(14), 0);
        row.addView(thumb, thumbParams);
        showThumbnail(item, thumb);

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        row.addView(texts, new LinearLayout.LayoutParams(0, WRAP, 1f));
        texts.addView(label(item.title(), 14, TEXT, false),
                new LinearLayout.LayoutParams(MATCH, WRAP));

        TextView meta = label(item.meta(), 12, FAINT, false);
        texts.addView(meta, lp(MATCH, WRAP, 0, 3, 0, 0));

        parent.addView(row, lp(MATCH, WRAP, 0, 0, 0, 0));
        row.setOnClickListener(v -> playRecording(item.uri));
        row.setOnLongClickListener(v -> {
            showRecordingActions(item);
            return true;
        });
    }

    /** First frame of the video, decoded off the UI thread and cached. */
    private void showThumbnail(final Recording item, final ImageView view) {
        final String key = item.uri.toString();
        Bitmap cached = thumbCache.get(key);
        if (cached != null) {
            view.setImageBitmap(cached);
            return;
        }
        view.setImageResource(R.drawable.ic_video);
        view.setColorFilter(FAINT);
        view.setPadding(dp(17), dp(17), dp(17), dp(17));

        thumbPool.execute(() -> {
            Bitmap frame = null;
            MediaMetadataRetriever retriever = new MediaMetadataRetriever();
            try {
                retriever.setDataSource(MainActivity.this, item.uri);
                frame = retriever.getFrameAtTime(0,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                if (frame == null && item.duration > 0) {
                    frame = retriever.getFrameAtTime(item.duration * 500,
                            MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                }
            } catch (Exception ignored) {
            } finally {
                try { retriever.release(); } catch (Exception ignored) { }
            }
            final Bitmap rounded = frame == null ? null : roundBitmap(frame, dp(10));
            if (rounded == null) return;
            thumbCache.put(key, rounded);
            handler.post(() -> {
                if (isFinishing() || view.getParent() == null) return;
                view.clearColorFilter();
                view.setPadding(0, 0, 0, 0);
                view.setImageBitmap(rounded);
            });
        });
    }

    /** Scales a video frame down and rounds its corners. */
    private static Bitmap roundBitmap(Bitmap source, int radius) {
        try {
            int width = source.getWidth();
            int height = source.getHeight();
            if (width <= 0 || height <= 0) return null;
            int max = 256;
            if (width > max || height > max) {
                float scale = Math.min((float) max / width, (float) max / height);
                Bitmap scaled = Bitmap.createScaledBitmap(source,
                        Math.max(1, Math.round(width * scale)),
                        Math.max(1, Math.round(height * scale)), true);
                if (scaled != source) source.recycle();
                source = scaled;
                width = source.getWidth();
                height = source.getHeight();
            }
            Bitmap out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(out);
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setShader(new BitmapShader(source,
                    Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
            canvas.drawRoundRect(new RectF(0, 0, width, height), radius, radius, paint);
            source.recycle();
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    private void showRecordingActions(final Recording item) {
        String[] items = {
                getString(R.string.action_open),
                getString(R.string.action_share),
                getString(R.string.action_delete)
        };
        showChoices(item.title(), items, -1, which -> {
            if (which == 0) playRecording(item.uri);
            else if (which == 1) shareRecording(item.uri);
            else confirmDelete(item);
        });
    }

    private void confirmDelete(final Recording item) {
        showCard(getString(R.string.delete_title),
                getString(R.string.delete_body, item.title()),
                getString(R.string.dialog_delete),
                () -> {
                    try {
                        getContentResolver().delete(item.uri, null, null);
                    } catch (Exception ignored) { }
                    loadRecordings();
                },
                getString(R.string.dialog_cancel), null);
    }

    private void playRecording(Uri uri) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "video/mp4")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
        } catch (Exception ignored) { }
    }

    private void shareRecording(Uri uri) {
        try {
            startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND)
                    .setType("video/mp4")
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                    getString(R.string.action_share)));
        } catch (Exception ignored) { }
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024L * 1024L) return Math.max(1, bytes / 1024) + " KB";
        double mb = bytes / (1024.0 * 1024.0);
        if (mb < 1024) return Math.round(mb) + " MB";
        return String.format(Locale.US, "%.1f GB", mb / 1024.0);
    }

    private static String formatLength(long ms) {
        long total = Math.max(0, ms) / 1000L;
        long hours = total / 3600;
        long minutes = (total % 3600) / 60;
        long seconds = total % 60;
        if (hours > 0) {
            return String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds);
        }
        return String.format(Locale.US, "%02d:%02d", minutes, seconds);
    }

    private static final class Recording {
        final Uri uri;
        final String name;
        final long added;
        final long duration;
        final long size;
        final int width;
        final int height;

        Recording(Uri uri, String name, long added, long duration, long size,
                  int width, int height) {
            this.uri = uri;
            this.name = name == null ? "" : name;
            this.added = added;
            this.duration = duration;
            this.size = size;
            this.width = width;
            this.height = height;
        }

        String title() {
            return new SimpleDateFormat("dd/MM/yyyy · HH:mm", Locale.getDefault())
                    .format(new Date(added * 1000L));
        }

        String meta() {
            StringBuilder out = new StringBuilder();
            if (duration > 0) out.append(formatLength(duration)).append("  ·  ");
            out.append(formatSize(size));
            if (width > 0 && height > 0) {
                out.append("  ·  ").append(width).append(" × ").append(height);
            }
            return out.toString();
        }
    }

    private void showScreen(int index) {
        screen = index;
        homeScroll.setVisibility(index == 0 ? View.VISIBLE : View.GONE);
        settingsScroll.setVisibility(index == 1 ? View.VISIBLE : View.GONE);
        recordingsScroll.setVisibility(index == 2 ? View.VISIBLE : View.GONE);
        int title = index == 0 ? R.string.app_name
                : index == 1 ? R.string.settings_title : R.string.nav_recordings;
        screenTitle.setText(title);
        backButton.setVisibility(index == 0 ? View.GONE : View.VISIBLE);
        settingsButton.setVisibility(index == 0 ? View.VISIBLE : View.GONE);
        styleNavItem(navRecord, navRecordIcon, navRecordLabel, index == 0);
        styleNavItem(navRecordings, navRecordingsIcon, navRecordingsLabel, index == 2);
        styleNavItem(navSettings, navSettingsIcon, navSettingsLabel, index == 1);
        animateScreenIn(index == 0 ? homeScroll : index == 1 ? settingsScroll : recordingsScroll);
        updateBackCallback();
        if (index == 1) {
            updateQualityValues();
            updateAudioValue();
            updateBatteryValue();
            updateTileValue();
            updateLanguageValue();
            updateStorageValue();
            updateOrientationValue();
        } else if (index == 2) {
            loadRecordings();
        }
    }

    /** A short fade and rise when a screen comes in. */
    private void animateScreenIn(final View view) {
        if (view == null) return;
        view.animate().cancel();
        view.setAlpha(0f);
        view.setTranslationY(dp(12));
        view.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(220)
                .setInterpolator(new android.view.animation.DecelerateInterpolator())
                .start();
    }

    /** Buttons shrink a little while pressed. */
    private void pressEffect(final View view) {
        if (view == null) return;
        view.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    v.animate().scaleX(0.96f).scaleY(0.96f).setDuration(90).start();
                    break;
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL:
                    v.animate().scaleX(1f).scaleY(1f).setDuration(140).start();
                    break;
                default:
                    break;
            }
            return false;
        });
    }

    private void styleNavItem(LinearLayout item, ImageView icon, TextView label, boolean active) {
        item.setBackground(active ? round(NAV_ACTIVE, 24, 0) : null);
        int color = active ? TEXT : FAINT;
        icon.setColorFilter(color);
        label.setTextColor(color);
    }

    private LinearLayout navItem(int iconRes, String text) {
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.HORIZONTAL);
        item.setGravity(Gravity.CENTER);
        item.setClickable(true);
        item.setFocusable(true);

        ImageView icon = new ImageView(this);
        icon.setImageResource(iconRes);
        icon.setColorFilter(FAINT);
        item.addView(icon, new LinearLayout.LayoutParams(dp(20), dp(20)));

        TextView label = label(text, 13, FAINT, false);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(WRAP, WRAP);
        labelParams.setMargins(dp(8), 0, 0, 0);
        item.addView(label, labelParams);
        pressEffect(item);
        return item;
    }

    private ImageView iconButton(int iconRes, int tint) {
        ImageView view = new ImageView(this);
        view.setImageResource(iconRes);
        view.setColorFilter(tint);
        view.setPadding(dp(9), dp(9), dp(9), dp(9));
        view.setClickable(true);
        view.setFocusable(true);
        view.setBackground(round(0x00000000, 20, 0));
        return view;
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(round(CARD, 14, BORDER));
        card.setPadding(dp(16), dp(2), dp(16), dp(2));
        return card;
    }

    private TextView addRow(LinearLayout parent, int iconRes, int nameRes, String value,
                            boolean chevron, Runnable onClick) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(14), 0, dp(14));

        ImageView icon = new ImageView(this);
        icon.setImageResource(iconRes);
        icon.setColorFilter(MUTED);
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(18), dp(18));
        iconParams.setMargins(0, 0, dp(12), 0);
        row.addView(icon, iconParams);

        TextView nameView = label(getString(nameRes), 14, TEXT, false);
        row.addView(nameView, new LinearLayout.LayoutParams(WRAP, WRAP));

        TextView valueView = label(value, 14, MUTED, false);
        valueView.setGravity(Gravity.END);
        valueView.setSingleLine(true);
        LinearLayout.LayoutParams valueParams =
                new LinearLayout.LayoutParams(0, WRAP, 1f);
        valueParams.setMargins(dp(12), 0, 0, 0);
        row.addView(valueView, valueParams);

        if (chevron) {
            ImageView chevronView = new ImageView(this);
            chevronView.setImageResource(R.drawable.ic_chevron);
            chevronView.setColorFilter(FAINT);
            LinearLayout.LayoutParams chevronParams =
                    new LinearLayout.LayoutParams(dp(18), dp(18));
            chevronParams.setMargins(dp(4), 0, 0, 0);
            row.addView(chevronView, chevronParams);
        }

        if (onClick != null) {
            row.setClickable(true);
            row.setFocusable(true);
            row.setOnClickListener(v -> onClick.run());
        }

        parent.addView(row, lp(MATCH, WRAP, 0, 0, 0, 0));
        return valueView;
    }

    private void divider(LinearLayout parent) {
        View line = new View(this);
        line.setBackgroundColor(BORDER);
        parent.addView(line, lp(MATCH, 1, 0, 0, 0, 0));
    }

    /** A small muted line inside a card, under the row it belongs to. */
    private void addHint(LinearLayout parent, int textRes) {
        TextView view = label(getString(textRes), 12, FAINT, false);
        view.setLineSpacing(dp(3), 1f);
        view.setPadding(0, dp(10), 0, dp(12));
        parent.addView(view, lp(MATCH, WRAP, 0, 0, 0, 0));
    }

    private TextView sectionLabel(int textRes) {
        TextView view = label(getString(textRes), 11, FAINT, true);
        view.setLetterSpacing(0.12f);
        return view;
    }

    private TextView label(CharSequence text, float size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, size);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.DEFAULT_BOLD);
        return view;
    }

    private void styleButton(Button button, int background) {
        button.setBackground(round(background, 12, 0));
        button.setTextColor(background == RED ? Color.WHITE : PRIMARY_FG);
    }

    private GradientDrawable round(int color, int radius, int stroke) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        if (stroke != 0) {
            drawable.setStroke(dp(1), stroke);
        }
        return drawable;
    }

    private LinearLayout.LayoutParams lp(int width, int height, int left, int top,
                                         int right, int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(width, height);
        params.setMargins(dp(left), dp(top), dp(right), dp(bottom));
        return params;
    }

    private int barHeight(String name) {
        int id = getResources().getIdentifier(name, "dimen", "android");
        return id > 0 ? getResources().getDimensionPixelSize(id) : dp(24);
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    // ---------- Values ----------

    private int[] nativeSize() {
        DisplayMetrics dm = new DisplayMetrics();
        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        wm.getDefaultDisplay().getRealMetrics(dm);
        return new int[]{dm.widthPixels, dm.heightPixels};
    }

    /**
     * The size the encoder is asked for: the exact screen size (rounded to an even
     * number, which is what MediaCodec needs), or the picked width with the same
     * aspect ratio. It is not rounded down to 16 any more, so a native recording
     * really is 1220 × 2712 instead of 1216 × 2704.
     */
    private int[] outputSize(int width) {
        int[] size = nativeSize();
        int screenWidth = Math.max(2, size[0]);
        int screenHeight = Math.max(2, size[1]);
        int outWidth;
        int outHeight;
        if (width <= 0) {
            outWidth = screenWidth;
            outHeight = screenHeight;
        } else {
            float scale = (float) width / screenWidth;
            outWidth = Math.round(screenWidth * scale);
            outHeight = Math.round(screenHeight * scale);
        }
        return new int[]{outWidth & ~1, outHeight & ~1};
    }

    private String dims(int width) {
        int[] size = outputSize(width);
        return size[0] + " × " + size[1];
    }

    private String resolutionClass(int width) {
        if (width >= 2160) return getString(R.string.class_4k);
        if (width >= 1440) return getString(R.string.class_2k);
        if (width >= 1080) return getString(R.string.class_fhd);
        if (width >= 720) return getString(R.string.class_hd);
        return getString(R.string.class_sd);
    }

    private String resolutionLabel() {
        if (resWidth <= 0) return getString(R.string.value_native, dims(0));
        return getString(R.string.value_class_size, resolutionClass(resWidth), dims(resWidth));
    }

    private boolean hevc() {
        return "hevc".equals(codec);
    }

    private String codecLabel() {
        return getString(hevc() ? R.string.value_codec_hevc : R.string.value_codec_avc);
    }

    /** The frame rate actually used: performance mode caps it at 60. */
    private int effectiveFps() {
        return perfMode ? Math.min(videoFps, 60) : videoFps;
    }

    private String summaryLine() {
        return dims(resWidth) + " · " + effectiveFps() + " fps · "
                + (hevc() ? "H.265" : "H.264") + " · " + bitrateMbps + " Mbps";
    }

    private void updateQualityValues() {
        if (resolutionValueView != null) resolutionValueView.setText(resolutionLabel());
        if (bitrateValueView != null) {
            bitrateValueView.setText(getString(R.string.value_mbps, bitrateMbps));
        }
        if (fpsValueView != null) {
            fpsValueView.setText(getString(R.string.value_fps, videoFps));
        }
        if (codecValueView != null) codecValueView.setText(codecLabel());
        if (performanceValueView != null) {
            performanceValueView.setText(perfMode
                    ? R.string.performance_on
                    : R.string.performance_off);
        }
        if (heroBody != null) heroBody.setText(summaryLine());
    }

    private void updateAudioValue() {
        if (audioValueView == null) return;
        int res;
        if (audioSource == ScreenRecorder.AUDIO_MIC) res = R.string.audio_mic;
        else if (audioSource == ScreenRecorder.AUDIO_INTERNAL) res = R.string.audio_internal;
        else res = R.string.audio_none;
        audioValueView.setText(getString(res));
    }

    private void updateLanguageValue() {
        if (languageValueView == null) return;
        languageValueView.setText(getString(
                "vi".equals(language) ? R.string.language_vi : R.string.language_en));
    }

    private void updateTileValue() {
        if (tileValueView == null) return;
        tileValueView.setText(getString(prefs.getBoolean("tileAdded", false)
                ? R.string.value_tile_added
                : R.string.value_tile_missing));
    }

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "";
        }
    }

    private void status(int res, int color) {
        if (statusView == null) return;
        statusView.setVisibility(View.VISIBLE);
        statusView.setText(res);
        statusView.setTextColor(color);
    }

    // ---------- Dialogs ----------

    /** Cards pop in with a slight scale instead of just appearing. */
    private void animateDialogIn(View card) {
        if (card == null) return;
        card.setScaleX(0.92f);
        card.setScaleY(0.92f);
        card.setAlpha(0f);
        card.animate()
                .scaleX(1f)
                .scaleY(1f)
                .alpha(1f)
                .setDuration(180)
                .setInterpolator(new android.view.animation.DecelerateInterpolator())
                .start();
    }

    private Dialog showCard(CharSequence title, CharSequence body,
                            String primaryText, final Runnable primary,
                            String secondaryText, final Runnable secondary) {
        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(round(CARD, 20, BORDER));
        card.setPadding(dp(20), dp(20), dp(20), dp(14));

        card.addView(label(title, 17, TEXT, true), lp(MATCH, WRAP, 0, 0, 0, 0));

        TextView bodyView = label(body, 13, MUTED, false);
        bodyView.setLineSpacing(dp(3), 1f);
        card.addView(bodyView, lp(MATCH, WRAP, 0, 10, 0, 0));

        if (primaryText != null) {
            Button primaryButton = new Button(this);
            primaryButton.setAllCaps(false);
            primaryButton.setText(primaryText);
            primaryButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            primaryButton.setTypeface(Typeface.DEFAULT_BOLD);
            primaryButton.setStateListAnimator(null);
            styleButton(primaryButton, PRIMARY);
            primaryButton.setOnClickListener(v -> {
                dialog.dismiss();
                if (primary != null) primary.run();
            });
            card.addView(primaryButton, lp(MATCH, dp(46), 0, 20, 0, 0));
        }

        if (secondaryText != null) {
            Button secondaryButton = new Button(this);
            secondaryButton.setAllCaps(false);
            secondaryButton.setText(secondaryText);
            secondaryButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            secondaryButton.setStateListAnimator(null);
            secondaryButton.setBackground(round(0x00000000, 12, 0));
            secondaryButton.setTextColor(MUTED);
            secondaryButton.setOnClickListener(v -> {
                dialog.dismiss();
                if (secondary != null) secondary.run();
            });
            card.addView(secondaryButton, lp(MATCH, dp(44), 0, 4, 0, 0));
        }

        FrameLayout holder = new FrameLayout(this);
        holder.setPadding(dp(22), dp(22), dp(22), dp(22));
        holder.addView(card, new FrameLayout.LayoutParams(MATCH, WRAP));
        dialog.setContentView(holder);

        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setDimAmount(0.65f);
            window.setLayout(MATCH, WRAP);
        }
        dialog.show();
        if (window != null) window.setLayout(MATCH, WRAP);
        animateDialogIn(card);
        return dialog;
    }

    private interface OnPick {
        void pick(int index);
    }

    private interface OnConfirm {
        /** @return false to keep the dialog open (the input was not valid). */
        boolean run();
    }

    /** A card with a custom view inside (the folder name field). */
    private Dialog showCustomCard(CharSequence title, View content, String primaryText,
                                  final OnConfirm primary, String secondaryText,
                                  final Runnable secondary) {
        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(round(CARD, 20, BORDER));
        card.setPadding(dp(20), dp(20), dp(20), dp(14));

        card.addView(label(title, 17, TEXT, true), lp(MATCH, WRAP, 0, 0, 0, 0));
        card.addView(content, lp(MATCH, WRAP, 0, 14, 0, 0));

        Button primaryButton = new Button(this);
        primaryButton.setAllCaps(false);
        primaryButton.setText(primaryText);
        primaryButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        primaryButton.setTypeface(Typeface.DEFAULT_BOLD);
        primaryButton.setStateListAnimator(null);
        styleButton(primaryButton, PRIMARY);
        primaryButton.setOnClickListener(v -> {
            if (primary == null || primary.run()) dialog.dismiss();
        });
        card.addView(primaryButton, lp(MATCH, dp(46), 0, 18, 0, 0));

        if (secondaryText != null) {
            Button secondaryButton = new Button(this);
            secondaryButton.setAllCaps(false);
            secondaryButton.setText(secondaryText);
            secondaryButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            secondaryButton.setStateListAnimator(null);
            secondaryButton.setBackground(round(0x00000000, 12, 0));
            secondaryButton.setTextColor(MUTED);
            secondaryButton.setOnClickListener(v -> {
                dialog.dismiss();
                if (secondary != null) secondary.run();
            });
            card.addView(secondaryButton, lp(MATCH, dp(44), 0, 4, 0, 0));
        }

        FrameLayout holder = new FrameLayout(this);
        holder.setPadding(dp(22), dp(22), dp(22), dp(22));
        holder.addView(card, new FrameLayout.LayoutParams(MATCH, WRAP));
        dialog.setContentView(holder);

        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setDimAmount(0.65f);
            window.setLayout(MATCH, WRAP);
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        }
        dialog.show();
        if (window != null) window.setLayout(MATCH, WRAP);
        animateDialogIn(card);
        return dialog;
    }

    private void showChoices(CharSequence title, String[] items, int checked,
                             final OnPick onPick) {
        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(round(CARD, 20, BORDER));
        card.setPadding(dp(8), dp(18), dp(8), dp(8));

        TextView titleView = label(title, 17, TEXT, true);
        titleView.setPadding(dp(12), 0, dp(12), dp(6));
        card.addView(titleView, lp(MATCH, WRAP, 0, 0, 0, 0));

        for (int i = 0; i < items.length; i++) {
            final int index = i;
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(12), dp(13), dp(12), dp(13));
            row.setBackground(round(0x00000000, 10, 0));
            row.setClickable(true);
            row.setFocusable(true);

            TextView item = label(items[i], 14, i == checked ? TEXT : MUTED, i == checked);
            row.addView(item, new LinearLayout.LayoutParams(0, WRAP, 1f));

            ImageView check = new ImageView(this);
            check.setImageResource(R.drawable.ic_check);
            check.setColorFilter(TEXT);
            check.setVisibility(i == checked ? View.VISIBLE : View.INVISIBLE);
            row.addView(check, new LinearLayout.LayoutParams(dp(18), dp(18)));

            row.setOnClickListener(v -> {
                dialog.dismiss();
                onPick.pick(index);
            });
            card.addView(row, lp(MATCH, WRAP, 0, 0, 0, 0));
        }

        Button cancel = new Button(this);
        cancel.setAllCaps(false);
        cancel.setText(getString(R.string.dialog_cancel));
        cancel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        cancel.setStateListAnimator(null);
        cancel.setBackground(round(0x00000000, 12, 0));
        cancel.setTextColor(MUTED);
        cancel.setOnClickListener(v -> dialog.dismiss());
        card.addView(cancel, lp(MATCH, dp(44), 0, 6, 0, 0));

        FrameLayout holder = new FrameLayout(this);
        holder.setPadding(dp(22), dp(22), dp(22), dp(22));
        holder.addView(card, new FrameLayout.LayoutParams(MATCH, WRAP));
        dialog.setContentView(holder);

        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setDimAmount(0.65f);
            window.setLayout(MATCH, WRAP);
        }
        dialog.show();
        if (window != null) window.setLayout(MATCH, WRAP);
        animateDialogIn(card);
    }

    // ---------- Setup ----------

    private void maybeShowSetup() {
        if (prefs.getBoolean("setupDone", false)) return;
        prefs.edit().putBoolean("setupDone", true).apply();
        handler.postDelayed(this::setupBattery, 500);
    }

    private void setupBattery() {
        if (batteryUnrestricted()) {
            setupTile();
            return;
        }
        showCard(getString(R.string.setup_battery_title),
                getString(R.string.setup_battery_body),
                getString(R.string.setup_allow),
                () -> {
                    requestBatteryExemption();
                    handler.postDelayed(this::setupTile, 600);
                },
                getString(R.string.setup_later),
                this::setupTile);
    }

    private void setupTile() {
        showCard(getString(R.string.setup_tile_title),
                getString(R.string.setup_tile_body),
                getString(R.string.setup_tile_add),
                this::requestTile,
                getString(R.string.setup_tile_skip),
                null);
    }

    // ---------- Actions ----------

    private boolean batteryUnrestricted() {
        if (Build.VERSION.SDK_INT < 23) return true;
        try {
            PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
            return power.isIgnoringBatteryOptimizations(getPackageName());
        } catch (Exception e) {
            return false;
        }
    }

    private void updateBatteryValue() {
        if (batteryValueView == null) return;
        batteryValueView.setText(getString(batteryUnrestricted()
                ? R.string.battery_on
                : R.string.battery_off));
    }

    private void requestBatteryExemption() {
        if (batteryUnrestricted()) {
            status(R.string.status_battery_already, MUTED);
            updateBatteryValue();
            return;
        }
        try {
            Intent request = new Intent(
                    android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName()));
            startActivity(request);
        } catch (Exception e) {
            try {
                startActivity(new Intent(
                        android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Exception ignored) {
                status(R.string.status_battery_manual, MUTED);
            }
        }
    }

    private void requestTile() {
        if (Build.VERSION.SDK_INT < 24) {
            status(R.string.status_tile_failed, MUTED);
            return;
        }
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                StatusBarManager statusBar =
                        (StatusBarManager) getSystemService(STATUS_BAR_SERVICE);
                statusBar.requestAddTileService(
                        new ComponentName(this, QsTileService.class),
                        getString(R.string.app_name),
                        Icon.createWithResource(this, R.drawable.ic_tile),
                        getMainExecutor(),
                        result -> {
                            boolean ok = result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED
                                    || result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED;
                            prefs.edit().putBoolean("tileAdded", ok).apply();
                            updateTileValue();
                            status(ok ? R.string.status_tile_added : R.string.status_tile_failed,
                                    ok ? GREEN : MUTED);
                        });
                return;
            } catch (Exception ignored) { }
        }
        prefs.edit().putBoolean("tileAdded", false).apply();
        updateTileValue();
        status(R.string.status_tile_failed, MUTED);
    }

    private void chooseResolution() {
        if (blockWhileRecording()) return;
        final int[] widths = {0, 2160, 1440, 1080, 720, 480};
        final String[] names = new String[widths.length];
        for (int i = 0; i < widths.length; i++) {
            names[i] = widths[i] == 0
                    ? getString(R.string.value_native, dims(0))
                    : getString(R.string.value_class_size,
                            resolutionClass(widths[i]), dims(widths[i]));
        }
        int checked = 0;
        for (int i = 0; i < widths.length; i++) {
            if (widths[i] == resWidth) checked = i;
        }
        showChoices(getString(R.string.title_resolution), names, checked, which -> {
            resWidth = widths[which];
            prefs.edit().putInt("resWidth", resWidth).apply();
            updateQualityValues();
        });
    }

    private void chooseSaveFolder() {
        if (blockWhileRecording()) return;
        final String[] presets = {
                "Movies/Screen Recorder",
                "Movies/DenRecorder",
                "DCIM/DenRecorder",
                "Movies",
                "Download"
        };
        final String[] items = new String[presets.length + 1];
        int checked = -1;
        for (int i = 0; i < presets.length; i++) {
            items[i] = presets[i];
            if (presets[i].equals(saveFolder())) checked = i;
        }
        items[presets.length] = getString(R.string.folder_custom);
        showChoices(getString(R.string.title_folder), items, checked, which -> {
            if (which < presets.length) {
                applySaveFolder(presets[which]);
            } else {
                showFolderInput();
            }
        });
    }

    private void showFolderInput() {
        final EditText input = new EditText(this);
        input.setText(saveFolder());
        input.setHint(getString(R.string.folder_input_hint));
        input.setTextColor(TEXT);
        input.setHintTextColor(FAINT);
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        input.setBackground(round(NAV_ACTIVE, 10, BORDER));
        input.setPadding(dp(14), dp(12), dp(14), dp(12));
        input.setSingleLine(true);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setSelection(input.getText().length());

        showCustomCard(getString(R.string.title_folder), input,
                getString(R.string.dialog_save),
                () -> {
                    String value = input.getText().toString().trim();
                    value = value.replaceAll("^/+", "").replaceAll("/+$", "");
                    if (value.isEmpty() || value.contains("..")) {
                        Toast.makeText(this, R.string.folder_invalid, Toast.LENGTH_SHORT).show();
                        return false;
                    }
                    applySaveFolder(value);
                    return true;
                },
                getString(R.string.dialog_cancel), null);
    }

    private void applySaveFolder(String folder) {
        prefs.edit().putString("saveFolder", folder).apply();
        updateStorageValue();
        status(getString(R.string.status_folder_changed, folder), MUTED);
        if (screen == 2) loadRecordings();
    }

    private void updateStorageValue() {
        if (storageValueView != null) storageValueView.setText(saveFolder());
        if (recordingsHint != null) {
            recordingsHint.setText(getString(R.string.hint_recordings, saveFolder()));
        }
    }

    // ---------- Screen orientation ----------

    /**
     * The rotation the screen is kept in while a recording runs. It is locked
     * for the whole recording (the phone's own rotation setting is restored
     * afterwards), so a finished video keeps one orientation.
     */
    private void chooseOrientation() {
        final String[] values = {"auto", "portrait", "landscape"};
        final String[] names = {
                getString(R.string.orientation_auto),
                getString(R.string.orientation_portrait),
                getString(R.string.orientation_landscape)
        };
        int checked = 0;
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(orientation)) checked = i;
        }
        showChoices(getString(R.string.title_orientation), names, checked, which -> {
            orientation = values[which];
            prefs.edit().putString("orientation", orientation).apply();
            updateOrientationValue();
            if (!"auto".equals(orientation) && !RotationLock.canWrite(this)) {
                showCard(getString(R.string.orientation_permission_title),
                        getString(R.string.orientation_permission_body),
                        getString(R.string.orientation_permission_grant),
                        this::requestRotationPermission,
                        getString(R.string.orientation_permission_later), null);
            }
        });
    }

    /** Android needs this special grant before an app may change rotation. */
    private void requestRotationPermission() {
        try {
            startActivityForResult(new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                    Uri.parse("package:" + getPackageName())), REQ_WRITE_SETTINGS);
        } catch (Exception e) {
            try {
                startActivityForResult(new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS),
                        REQ_WRITE_SETTINGS);
            } catch (Exception ignored) {
                status(R.string.orientation_permission_unavailable, RED);
            }
        }
    }

    private void updateOrientationValue() {
        if (orientationValueView == null) return;
        int res = "portrait".equals(orientation) ? R.string.orientation_portrait
                : "landscape".equals(orientation) ? R.string.orientation_landscape
                : R.string.orientation_auto;
        orientationValueView.setText(res);
    }

    private void chooseBitrate() {
        if (blockWhileRecording()) return;
        final int[] values = {15, 20, 25, 32, 40, 50};
        final String[] names = new String[values.length];
        int checked = 0;
        for (int i = 0; i < values.length; i++) {
            names[i] = getString(R.string.value_mbps, values[i]);
            if (values[i] == bitrateMbps) checked = i;
        }
        showChoices(getString(R.string.title_quality), names, checked, which -> {
            bitrateMbps = values[which];
            prefs.edit().putInt("bitrateMbps", bitrateMbps).apply();
            updateQualityValues();
        });
    }

    private void chooseFps() {
        if (blockWhileRecording()) return;
        final int[] values = {30, 60, 90, 120};
        final String[] names = new String[values.length];
        int checked = 1;
        for (int i = 0; i < values.length; i++) {
            names[i] = getString(R.string.value_fps, values[i]);
            if (values[i] == videoFps) checked = i;
        }
        showChoices(getString(R.string.title_fps), names, checked, which -> {
            videoFps = values[which];
            prefs.edit().putInt("fps", videoFps).apply();
            updateQualityValues();
        });
    }

    /**
     * Caps the recording at 60 fps so a game running next to it keeps its
     * frame rate. Turning it off lets the picked frame rate through.
     */
    private void togglePerformance() {
        if (blockWhileRecording()) return;
        perfMode = !perfMode;
        prefs.edit().putBoolean("perfMode", perfMode).apply();
        updateQualityValues();
        status(perfMode ? R.string.performance_on : R.string.performance_off, MUTED);
    }

    private void chooseCodec() {
        if (blockWhileRecording()) return;
        final String[] values = {"avc", "hevc"};
        final String[] names = {
                getString(R.string.codec_avc_item),
                getString(R.string.codec_hevc_item)
        };
        showChoices(getString(R.string.title_codec), names, hevc() ? 1 : 0, which -> {
            codec = values[which];
            prefs.edit().putString("codec", codec).apply();
            updateQualityValues();
        });
    }

    private void chooseAudioSource() {
        if (blockWhileRecording()) return;
        final int[] values = {
                ScreenRecorder.AUDIO_INTERNAL,
                ScreenRecorder.AUDIO_MIC,
                ScreenRecorder.AUDIO_NONE
        };
        final String[] names = {
                getString(R.string.audio_internal),
                getString(R.string.audio_mic),
                getString(R.string.audio_none)
        };
        int checked = 0;
        for (int i = 0; i < values.length; i++) {
            if (values[i] == audioSource) checked = i;
        }
        showChoices(getString(R.string.title_audio), names, checked, which -> {
            audioSource = values[which];
            prefs.edit().putInt("audio", audioSource).apply();
            updateAudioValue();
        });
    }

    private void chooseLanguage() {
        final String[] values = {"en", "vi"};
        final String[] names = {
                getString(R.string.language_en),
                getString(R.string.language_vi)
        };
        showChoices(getString(R.string.title_language), names,
                "vi".equals(language) ? 1 : 0, which -> {
                    language = values[which];
                    prefs.edit().putString(LocaleHelper.KEY, language)
                            .putBoolean("openSettingsNext", true).apply();
                    try {
                        if (Build.VERSION.SDK_INT >= 24) {
                            TileService.requestListeningState(this,
                                    new ComponentName(this, QsTileService.class));
                        }
                    } catch (Exception ignored) { }
                    recreate();
                });
    }

    private boolean blockWhileRecording() {
        if (!RecorderService.isRecording) return false;
        status(R.string.status_stop_first, MUTED);
        return true;
    }

    private void onMainButton() {
        if (RecorderService.isRecording) {
            RecorderService.requestStop(this);
            status(R.string.status_stopping, MUTED);
            return;
        }
        beginStart();
    }

    private void beginStart() {
        // A locked orientation only works with the "modify system settings"
        // grant; offer it here instead of silently recording unlocked.
        if (!"auto".equals(orientation) && !RotationLock.canWrite(this)) {
            showCard(getString(R.string.orientation_permission_title),
                    getString(R.string.orientation_permission_body),
                    getString(R.string.orientation_permission_grant),
                    this::requestRotationPermission,
                    getString(R.string.orientation_permission_skip),
                    this::continueStart);
            return;
        }
        continueStart();
    }

    private void continueStart() {
        List<String> needed = new ArrayList<>();
        // Before Android 10 "device audio" falls back to the microphone.
        boolean needsMic = audioSource == ScreenRecorder.AUDIO_MIC
                || (audioSource == ScreenRecorder.AUDIO_INTERNAL && Build.VERSION.SDK_INT < 29);
        if (needsMic && !hasPermission(Manifest.permission.RECORD_AUDIO)) {
            needed.add(Manifest.permission.RECORD_AUDIO);
        }
        if (Build.VERSION.SDK_INT <= 28 && !hasPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
            // Android 9 and older save the video as a plain file on the card.
            needed.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        }
        if (Build.VERSION.SDK_INT >= 33
                && !hasPermission(Manifest.permission.POST_NOTIFICATIONS)) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (!needed.isEmpty()) {
            pendingStart = true;
            requestPermissions(needed.toArray(new String[0]), REQ_PERMISSIONS);
            return;
        }
        askProjection();
    }

    /** checkSelfPermission exists from Android 6; older versions grant at install. */
    private boolean hasPermission(String permission) {
        if (Build.VERSION.SDK_INT < 23) {
            return getPackageManager().checkPermission(permission, getPackageName())
                    == PackageManager.PERMISSION_GRANTED;
        }
        return checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    private void startRecorderService(Intent intent) {
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }

    private void askProjection() {
        MediaProjectionManager manager =
                (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        try {
            Intent capture;
            if (Build.VERSION.SDK_INT >= 34) {
                // Ask for the whole display up front, so the user does not have to
                // switch the scope from "one app" every time.
                try {
                    capture = manager.createScreenCaptureIntent(
                            MediaProjectionConfig.createConfigForDefaultDisplay());
                } catch (Exception e) {
                    capture = manager.createScreenCaptureIntent();
                }
            } else {
                capture = manager.createScreenCaptureIntent();
            }
            projectionRequested = true;
            startActivityForResult(capture, REQ_PROJECTION);
            status(R.string.status_allow_capture, MUTED);
        } catch (Exception e) {
            projectionRequested = false;
            status(R.string.status_capture_unavailable, RED);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_PERMISSIONS || !pendingStart) return;
        pendingStart = false;

        boolean needsMic = audioSource == ScreenRecorder.AUDIO_MIC
                || (audioSource == ScreenRecorder.AUDIO_INTERNAL && Build.VERSION.SDK_INT < 29);
        if (needsMic && !hasPermission(Manifest.permission.RECORD_AUDIO)) {
            audioSource = ScreenRecorder.AUDIO_NONE;
            prefs.edit().putInt("audio", audioSource).apply();
            updateAudioValue();
            status(R.string.status_mic_denied, MUTED);
        }
        askProjection();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_WRITE_SETTINGS) {
            updateOrientationValue();
            status(RotationLock.canWrite(this)
                    ? R.string.orientation_permission_on
                    : R.string.orientation_permission_off, MUTED);
            return;
        }
        if (requestCode != REQ_PROJECTION) return;
        projectionRequested = false;
        if (resultCode != RESULT_OK || data == null) {
            status(R.string.status_capture_denied, MUTED);
            return;
        }

        dialogShown = false;
        sawRecording = false;

        Intent intent = new Intent(this, RecorderService.class)
                .setAction(RecorderService.ACTION_START)
                .putExtra(RecorderService.EXTRA_RESULT_CODE, resultCode)
                .putExtra(RecorderService.EXTRA_DATA, data)
                .putExtra(RecorderService.EXTRA_AUDIO, audioSource);
        try {
            startRecorderService(intent);
            status(R.string.status_starting, MUTED);
        } catch (Exception e) {
            status(getString(R.string.status_start_failed, String.valueOf(e.getMessage())), RED);
        }
    }

    private void status(String text, int color) {
        if (statusView == null) return;
        statusView.setVisibility(View.VISIBLE);
        statusView.setText(text);
        statusView.setTextColor(color);
    }

    // ---------- State refresh ----------

    private void updateUi() {
        boolean recording = RecorderService.isRecording;
        if (recording) {
            sawRecording = true;
            stopDetectedAt = 0;
            if (!uiRecording) {
                uiRecording = true;
                mainButton.setText(R.string.stop_recording);
                styleButton(mainButton, RED);
                startDotAnimation();
            }
            long elapsed = System.currentTimeMillis() - RecorderService.startedAt;
            heroTitle.setText(getString(R.string.hero_recording_title)
                    + "  " + formatDuration(elapsed));
            statusView.setVisibility(View.GONE);
        } else {
            if (uiRecording) {
                uiRecording = false;
                mainButton.setText(R.string.start_recording);
                styleButton(mainButton, PRIMARY);
                heroTitle.setText(R.string.hero_ready_title);
                stopDotAnimation();
            }
            if (sawRecording) {
                if (stopDetectedAt == 0) stopDetectedAt = System.currentTimeMillis();
                boolean pending = RecorderService.lastUri == null
                        && RecorderService.lastError == null;
                if (pending && System.currentTimeMillis() - stopDetectedAt < 10_000) {
                    status(R.string.status_saving, MUTED);
                    return;
                }
                sawRecording = false;
                stopDetectedAt = 0;
                showResultDialog(RecorderService.lastUri, RecorderService.lastError);
            }
            if (RecorderService.lastError != null) {
                status(RecorderService.lastError, RED);
            } else {
                status(getString(R.string.status_ready, saveFolder()), MUTED);
            }
        }
    }

    /** The red dot next to "Recording" pulses slowly. */
    private void startDotAnimation() {
        if (recordingDot == null) return;
        recordingDot.setVisibility(View.VISIBLE);
        if (dotAnimator != null) return;
        dotAnimator = ValueAnimator.ofFloat(1f, 0.3f);
        dotAnimator.setDuration(650);
        dotAnimator.setRepeatCount(ValueAnimator.INFINITE);
        dotAnimator.setRepeatMode(ValueAnimator.REVERSE);
        dotAnimator.addUpdateListener(animation -> {
            if (recordingDot != null) {
                recordingDot.setAlpha((float) animation.getAnimatedValue());
            }
        });
        dotAnimator.start();
    }

    private void stopDotAnimation() {
        if (dotAnimator != null) {
            dotAnimator.cancel();
            dotAnimator = null;
        }
        if (recordingDot != null) {
            recordingDot.setAlpha(1f);
            recordingDot.setVisibility(View.GONE);
        }
    }

    private void showResultDialog(final Uri uri, String error) {
        if (dialogShown) return;
        if (uri == null && error == null) return;
        dialogShown = true;

        if (uri != null) {
            String detail = RecorderService.lastSummary;
            String message = getString(R.string.saved_body, saveFolder());
            if (detail != null) message = message + "\n\n" + detail;
            showCard(getString(R.string.saved_title), message,
                    getString(R.string.dialog_open),
                    () -> {
                        try {
                            Intent view = new Intent(Intent.ACTION_VIEW)
                                    .setDataAndType(uri, "video/mp4")
                                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                            startActivity(view);
                        } catch (Exception ignored) { }
                    },
                    getString(R.string.dialog_close), null);
        } else {
            showCard(getString(R.string.failed_title), error,
                    getString(R.string.dialog_ok), null, null, null);
        }
    }

    private static String formatDuration(long ms) {
        long total = Math.max(0, ms) / 1000L;
        return String.format(Locale.US, "%02d:%02d", total / 60, total % 60);
    }
}
