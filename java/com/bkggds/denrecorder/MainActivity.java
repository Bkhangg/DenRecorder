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
    private static final int REQ_OVERLAY = 1004;
    private static final int REQ_FOLDER = 1005;
    private static final int REQ_SHIZUKU = 1006;
    private static final int REQ_SHIZUKU_FAST = 1011;
    private static final int REQ_REGION_OVERLAY = 1012;

    /** Every file DenRecorder writes starts with this name. */
    private static final String SCREEN_RECORDING_PREFIX = "DenRecorder_";

    // Shadcn-style palette (zinc scale).
    // Shadcn palettes. The dark one is the original look; Settings → Theme
    // switches to the light one or follows the system.
    private int BG;
    private int CARD;
    private int BORDER;
    private int TEXT;
    private int MUTED;
    private int FAINT;
    private int PRIMARY;
    private int PRIMARY_FG;
    private int RED;
    private int GREEN;
    private int NAV;
    private int NAV_ACTIVE;
    private boolean lightTheme;

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
    private LinearLayout homeList;
    private LinearLayout settingsList;
    private LinearLayout recordingsPageList;
    private boolean settingsRevealed;
    private boolean recordingsRevealed;

    private TextView heroTitle;
    private TextView heroBody;
    private TextView statusView;
    private LinearLayout mainButton;
    private ImageView mainIcon;
    private TextView mainLabel;

    private TextView resolutionValueView;
    private TextView regionValueView;
    /** Set when the area frame was asked for but the overlay permission is missing. */
    private boolean pendingRegionPick;
    private TextView bitrateValueView;
    private TextView fpsValueView;
    private TextView codecValueView;
    private TextView audioValueView;
    private TextView batteryValueView;
    private TextView tileValueView;
    private TextView languageValueView;
    private TextView themeValueView;
    private TextView islandValueView;
    private TextView islandModeValueView;
    private TextView fastStartValueView;
    /** Set while the Shizuku permission request is on its way. */
    private boolean islandRequestPending;
    private boolean fastStartRequestPending;
    private TextView storageValueView;
    private TextView orientationValueView;
    private TextView drawValueView;
    private TextView performanceValueView;
    private View recordingDot;
    private ValueAnimator dotAnimator;

    private LinearLayout recordingsList;
    private TextView recordingsSummary;
    private TextView recordingsHint;
    private TextView recentTab;
    private TextView allTab;
    private boolean showAllRecordings;
    private boolean recordingsSelectMode;
    /** What the rows on screen were built from, to skip needless rebuilds. */
    private String recordingsSignature = "";
    private boolean recordingsLoaded;
    /** The last list read, so changing the mode can redraw without a read. */
    private final List<Recording> recordingsCache = new ArrayList<>();
    private boolean recordingsQuerying;
    private boolean recordingsQueryAgain;
    private final java.util.LinkedHashSet<String> selectedRecordings =
            new java.util.LinkedHashSet<>();
    private TextView selectButton;
    private TextView deleteSelectedButton;

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
    private String theme = "auto";
    private String orientation = "auto";
    private boolean drawOverlay = true;
    private int drawSize = 1;
    private boolean drawAutoHide;
    private TextView drawSizeValueView;
    private TextView drawModeValueView;
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
    /** Reads the theme choice and fills the palette that matches it. */
    private void applyTheme() {
        String mode = prefs == null ? "auto" : prefs.getString("theme", "auto");
        boolean light = "light".equals(mode) || ("auto".equals(mode) && systemIsLight());
        lightTheme = light;
        if (light) {
            BG = Color.parseColor("#FFFFFF");
            CARD = Color.parseColor("#FFFFFF");
            BORDER = Color.parseColor("#E4E4E7");
            TEXT = Color.parseColor("#09090B");
            MUTED = Color.parseColor("#52525B");
            FAINT = Color.parseColor("#71717A");
            PRIMARY = Color.parseColor("#18181B");
            PRIMARY_FG = Color.parseColor("#FAFAFA");
            RED = Color.parseColor("#DC2626");
            GREEN = Color.parseColor("#16A34A");
            NAV = Color.parseColor("#FFFFFF");
            NAV_ACTIVE = Color.parseColor("#F4F4F5");
        } else {
            BG = Color.parseColor("#09090B");
            CARD = Color.parseColor("#111113");
            BORDER = Color.parseColor("#27272A");
            TEXT = Color.parseColor("#FAFAFA");
            MUTED = Color.parseColor("#A1A1AA");
            FAINT = Color.parseColor("#71717A");
            PRIMARY = Color.parseColor("#FAFAFA");
            PRIMARY_FG = Color.parseColor("#09090B");
            RED = Color.parseColor("#EF4444");
            GREEN = Color.parseColor("#22C55E");
            NAV = Color.parseColor("#18181B");
            NAV_ACTIVE = Color.parseColor("#27272A");
        }
    }

    private boolean systemIsLight() {
        try {
            int mode = getResources().getConfiguration().uiMode
                    & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
            return mode != android.content.res.Configuration.UI_MODE_NIGHT_YES;
        } catch (Exception e) {
            return false;
        }
    }

    /** Status and navigation bars follow the theme, icons included. */
    private void styleSystemBars() {
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        int flags = 0;
        if (Build.VERSION.SDK_INT >= 23) {
            flags |= lightTheme ? View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR : 0;
        }
        if (Build.VERSION.SDK_INT >= 26) {
            flags |= lightTheme ? View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR : 0;
        }
        getWindow().getDecorView().setSystemUiVisibility(flags);
    }

    private String saveFolder() {
        String folder = prefs.getString("saveFolder", "Movies/Screen Recorder");
        if (folder == null) folder = "Movies/Screen Recorder";
        folder = folder.replaceAll("^/+", "").replaceAll("/+$", "");
        return folder.isEmpty() ? "Movies/Screen Recorder" : folder;
    }

    /** The area frame runs in another window; it says so when it was saved. */
    private final android.content.BroadcastReceiver regionSaved =
            new android.content.BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    updateRegionValue();
                }
            };

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            updateUi();
            // A second is only needed for the timer; with nothing recording the
            // screen is static and a slower check costs nothing.
            handler.postDelayed(this, RecorderService.isRecording || sawRecording
                    ? 1_000 : 2_500);
        }
    };

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleHelper.wrap(newBase));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(LocaleHelper.PREFS, MODE_PRIVATE);
        try {
            android.content.IntentFilter filter =
                    new android.content.IntentFilter(RegionPickerService.ACTION_SAVED);
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(regionSaved, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(regionSaved, filter);
            }
        } catch (Exception ignored) { }
        // A recording that was killed (update, force-stop, low memory) can leave
        // the screen rotation locked where it recorded; opening the app puts the
        // user's own setting back.
        RotationLock.restoreIfStale(this);
        applyTheme();
        styleSystemBars();

        audioSource = prefs.getInt("audio", ScreenRecorder.AUDIO_INTERNAL);
        resWidth = prefs.getInt("resWidth", 0);
        videoFps = prefs.getInt("fps", 60);
        bitrateMbps = prefs.getInt("bitrateMbps", 32);
        codec = prefs.getString("codec", "avc");
        language = prefs.getString(LocaleHelper.KEY, "en");
        theme = prefs.getString("theme", "auto");
        orientation = prefs.getString("orientation", "auto");
        perfMode = prefs.getBoolean("perfMode", true);
        drawOverlay = prefs.getBoolean("drawOverlay", true);
        drawSize = prefs.getInt("drawSize", 1);
        drawAutoHide = prefs.getBoolean("drawAutoHide", false);
        handler = new Handler(Looper.getMainLooper());
        try {
            rikka.shizuku.Shizuku.addRequestPermissionResultListener(islandPermissionListener);
        } catch (Throwable ignored) { }

        buildUi();
        boolean openSettings = prefs.getBoolean("openSettingsNext", false);
        if (openSettings) prefs.edit().putBoolean("openSettingsNext", false).apply();
        showScreen(openSettings ? 1 : 0);
        updateQualityValues();
        updateAudioValue();
        updateBatteryValue();
        updateTileValue();
        updateFastStartValue();
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
    public void onConfigurationChanged(android.content.res.Configuration configuration) {
        super.onConfigurationChanged(configuration);
        if (!"auto".equals(prefs.getString("theme", "auto"))) return;
        boolean wasLight = lightTheme;
        if (wasLight != systemIsLight()) recreate();
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.removeCallbacks(poll);
        handler.post(poll);
        updateBatteryValue();
        // Keep the phone's own rotation at hand, so a game that turns the
        // screen sideways can be undone when the recording ends.
        RotationLock.rememberUserLock(this);
        updateRegionValue();
        // A recording that went on while another app was in front: bring the
        // pulsing dot back now that this screen is the one in front again.
        if (uiRecording && RecorderService.isRecording) startDotAnimation();
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Nothing here has to run while another app is in front: the recording
        // carries on in the service, and the poll and the dot (a 60 fps
        // animation of its own) are put away so a game and the shade stay
        // smooth.
        handler.removeCallbacks(poll);
        stopDotAnimation();
    }

    /**
     * The notification shade, the capture dialog or a floating window takes the
     * focus away without pausing this screen. The pulsing dot is a redraw every
     * frame, so it is stopped for as long as something else is on top.
     */
    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (!hasFocus) {
            // The shade, the capture dialog or a floating window is on top:
            // nothing on this screen is visible, so neither the refresh nor the
            // pulsing dot (a redraw every frame) has to run.
            handler.removeCallbacks(poll);
            stopDotAnimation();
        } else {
            handler.removeCallbacks(poll);
            handler.post(poll);
            if (uiRecording && RecorderService.isRecording) startDotAnimation();
        }
    }

    @Override
    protected void onDestroy() {
        try {
            unregisterReceiver(regionSaved);
        } catch (Exception ignored) { }
        try {
            rikka.shizuku.Shizuku.removeRequestPermissionResultListener(islandPermissionListener);
        } catch (Throwable ignored) { }
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

        // Only the screen that opens first is built here. Settings and the
        // recordings are built the first time they are opened: building all
        // three made the app take a noticeable moment to show its window.
        buildHome();

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
        homeList = list;

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

        // Icon and label sit together in the middle: a Button would push the
        // icon to its edge, far from the text.
        mainButton = new LinearLayout(this);
        mainButton.setOrientation(LinearLayout.HORIZONTAL);
        mainButton.setGravity(Gravity.CENTER);
        mainButton.setClickable(true);
        mainButton.setFocusable(true);

        mainIcon = new ImageView(this);
        mainButton.addView(mainIcon, new LinearLayout.LayoutParams(dp(20), dp(20)));

        mainLabel = label("", 16, PRIMARY_FG, true);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(WRAP, WRAP);
        labelParams.setMargins(dp(10), 0, 0, 0);
        mainButton.addView(mainLabel, labelParams);

        setMainButton(false);
        mainButton.setOnClickListener(v -> onMainButton());
        pressEffect(mainButton);
        list.addView(mainButton, lp(MATCH, dp(52), 0, 16, 0, 0));

        TextView hint = label(getString(R.string.hint_home), 12, FAINT, false);
        hint.setLineSpacing(dp(3), 1f);
        list.addView(hint, lp(MATCH, WRAP, 0, 18, 0, 0));

        // The home screen is on show at start: let its parts rise in.
        revealChildren(list, 55);
    }

    private void buildSettings() {
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        settingsScroll.addView(list, new ScrollView.LayoutParams(MATCH, WRAP));
        settingsList = list;

        list.addView(sectionLabel(R.string.section_capture), lp(MATCH, WRAP, 0, 10, 0, 0));
        LinearLayout qualityCard = card();
        list.addView(qualityCard, lp(MATCH, WRAP, 0, 8, 0, 0));
        resolutionValueView = addRow(qualityCard, R.drawable.ic_screen, R.string.row_resolution,
                "", true, this::chooseResolution);
        divider(qualityCard);
        regionValueView = addRow(qualityCard, R.drawable.ic_rect, R.string.row_region,
                "", true, this::chooseRegion);
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
        fastStartValueView = addRow(generalCard, R.drawable.ic_chip,
                R.string.row_fast_start, "", true, this::toggleFastStart);
        divider(generalCard);
        languageValueView = addRow(generalCard, R.drawable.ic_language, R.string.row_language,
                "", true, this::chooseLanguage);
        divider(generalCard);
        themeValueView = addRow(generalCard, R.drawable.ic_theme, R.string.row_theme,
                "", true, this::chooseTheme);
        divider(generalCard);
        storageValueView = addRow(generalCard, R.drawable.ic_folder, R.string.row_storage,
                saveFolder(), true, this::chooseSaveFolder);
        divider(generalCard);
        orientationValueView = addRow(generalCard, R.drawable.ic_screen, R.string.row_orientation,
                "", true, this::chooseOrientation);
        divider(generalCard);
        addHint(generalCard, R.string.hint_orientation);
        divider(generalCard);
        drawValueView = addRow(generalCard, R.drawable.ic_pen, R.string.row_draw,
                "", true, this::toggleDrawOverlay);
        divider(generalCard);
        drawSizeValueView = addRow(generalCard, R.drawable.ic_rect, R.string.row_draw_size,
                "", true, this::chooseDrawSize);
        divider(generalCard);
        drawModeValueView = addRow(generalCard, R.drawable.ic_screen, R.string.row_draw_mode,
                "", true, this::chooseDrawMode);
        divider(generalCard);
        addHint(generalCard, R.string.hint_draw_mode);

        // Super Island is a Xiaomi feature, so the row only exists there.
        if (Island.isXiaomi()) {
            list.addView(sectionLabel(R.string.section_beta), lp(MATCH, WRAP, 0, 22, 0, 0));
            LinearLayout betaCard = card();
            list.addView(betaCard, lp(MATCH, WRAP, 0, 8, 0, 0));
            islandValueView = addRow(betaCard, R.drawable.ic_tile, R.string.row_island,
                    "", true, this::chooseIsland);
            divider(betaCard);
            islandModeValueView = addRow(betaCard, R.drawable.ic_rect,
                    R.string.row_island_mode, "", true, this::chooseIslandMode);
            divider(betaCard);
            addHint(betaCard, R.string.hint_island_beta);
        }

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
        recordingsPageList = list;

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

        LinearLayout summaryRow = new LinearLayout(this);
        summaryRow.setOrientation(LinearLayout.HORIZONTAL);
        summaryRow.setGravity(Gravity.CENTER_VERTICAL);
        list.addView(summaryRow, lp(MATCH, WRAP, 0, 12, 0, 0));

        recordingsSummary = label("", 12, FAINT, false);
        summaryRow.addView(recordingsSummary, new LinearLayout.LayoutParams(0, WRAP, 1f));

        deleteSelectedButton = label("", 12, RED, true);
        deleteSelectedButton.setPadding(dp(12), dp(8), dp(4), dp(8));
        deleteSelectedButton.setVisibility(View.GONE);
        deleteSelectedButton.setOnClickListener(v -> confirmDeleteSelected());
        summaryRow.addView(deleteSelectedButton, new LinearLayout.LayoutParams(WRAP, WRAP));

        selectButton = label(getString(R.string.select_recordings), 12, TEXT, true);
        selectButton.setPadding(dp(12), dp(8), dp(4), dp(8));
        selectButton.setOnClickListener(v -> setSelectMode(!recordingsSelectMode));
        summaryRow.addView(selectButton, new LinearLayout.LayoutParams(WRAP, WRAP));

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

    /**
     * Shows the recordings screen. Opening the tab never waits for MediaStore:
     * the list already read is drawn at once, and a background read only
     * rebuilds the rows when something really changed.
     */
    private void loadRecordings() {
        if (recordingsList == null) return;
        styleTabs();
        if (recordingsHint != null) {
            recordingsHint.setText(getString(R.string.hint_recordings, saveFolder()));
        }
        if (!recordingsLoaded) {
            // First visit: the rows arrive with the read below in a moment, so
            // there is nothing to draw (and no "no recordings" flash).
            if (recordingsSummary != null) recordingsSummary.setText("");
        }
        queryRecordings();
    }

    /** Reads the list off the UI thread; asking again while it runs reads once more. */
    private void queryRecordings() {
        if (recordingsQuerying) {
            recordingsQueryAgain = true;
            return;
        }
        recordingsQuerying = true;
        new Thread(() -> {
            final List<Recording> items = readAllRecordings();
            handler.post(() -> {
                recordingsQuerying = false;
                renderRecordings(items);
                if (recordingsQueryAgain) {
                    recordingsQueryAgain = false;
                    queryRecordings();
                }
            });
        }, "rec-query").start();
    }

    /** Every recording MediaStore knows about, newest first. */
    private List<Recording> readAllRecordings() {
        List<Recording> items = new ArrayList<>();
        // Recordings are found by their name, not by the folder, so the list
        // keeps showing everything after "Save to" was changed. "Download" goes
        // into MediaStore's downloads collection, everything else into videos.
        try {
            readRecordings(items, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, null);
            if (Build.VERSION.SDK_INT >= 29) {
                readRecordings(items, MediaStore.Downloads.EXTERNAL_CONTENT_URI, "video/mp4");
            }
        } catch (Exception ignored) { }
        // A video saved in Download shows up in both collections; keep it once.
        java.util.LinkedHashMap<String, Recording> unique = new java.util.LinkedHashMap<>();
        for (Recording item : items) {
            String key = item.name + "|" + item.size;
            if (!unique.containsKey(key)) unique.put(key, item);
        }
        items = new ArrayList<>(unique.values());
        java.util.Collections.sort(items, (a, b) -> Long.compare(b.added, a.added));
        if (items.size() > 500) items = new ArrayList<>(items.subList(0, 500));
        return items;
    }

    /** What a row shows, so an unchanged read does not touch the views at all. */
    private static String signatureOf(List<Recording> items) {
        StringBuilder out = new StringBuilder(items.size() * 48);
        for (Recording item : items) {
            out.append(item.name).append('|').append(item.size).append('|')
                    .append(item.duration).append('|').append(item.added).append('|')
                    .append(item.width).append('x').append(item.height).append(';');
        }
        return out.toString();
    }

    /** Fills the list, unless the same videos are already on screen. */
    private void renderRecordings(List<Recording> items) {
        renderRecordings(items, false);
    }

    /**
     * Fills the list. "force" draws the very same videos again, which the
     * select mode needs: the rows show different things (selection circles)
     * without the list itself having changed.
     */
    private void renderRecordings(List<Recording> items, boolean force) {
        if (recordingsList == null) return;
        String signature = signatureOf(items);
        // The cached list is handed in again to redraw it; copying it onto
        // itself would empty it, so it is only replaced by another list.
        if (items != recordingsCache) {
            recordingsCache.clear();
            recordingsCache.addAll(items);
        }
        if (!force && recordingsLoaded && signature.equals(recordingsSignature)) return;
        recordingsSignature = signature;
        recordingsLoaded = true;

        styleTabs();
        recordingsList.removeAllViews();

        // A selection only lasts as long as the video exists.
        java.util.LinkedHashSet<String> alive = new java.util.LinkedHashSet<>();
        for (Recording item : items) {
            if (selectedRecordings.contains(item.uri.toString())) {
                alive.add(item.uri.toString());
            }
        }
        selectedRecordings.retainAll(alive);

        long totalSize = 0;
        for (Recording item : items) totalSize += item.size;
        if (recordingsSummary != null) {
            recordingsSummary.setText(getString(R.string.recordings_count,
                    items.size(), formatSize(totalSize)));
        }
        updateSelectBar();

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
            addRecordingRow(card, items.get(i), i);
        }
    }

    private void readRecordings(List<Recording> out, Uri collection, String mimeFilter) {
        boolean scoped = Build.VERSION.SDK_INT >= 29;
        Cursor cursor = null;
        try {
            String[] projection = new String[]{
                    MediaStore.MediaColumns._ID,
                    MediaStore.MediaColumns.DISPLAY_NAME,
                    MediaStore.MediaColumns.DURATION,
                    MediaStore.MediaColumns.SIZE,
                    MediaStore.MediaColumns.DATE_ADDED,
                    MediaStore.MediaColumns.WIDTH,
                    MediaStore.MediaColumns.HEIGHT,
                    scoped ? MediaStore.MediaColumns.RELATIVE_PATH : MediaStore.MediaColumns.DATA
            };
            String selection = MediaStore.MediaColumns.DISPLAY_NAME + " LIKE ?";
            if (mimeFilter != null) selection += " AND " + MediaStore.MediaColumns.MIME_TYPE + "=?";
            String[] args = mimeFilter == null
                    ? new String[]{SCREEN_RECORDING_PREFIX + "%"}
                    : new String[]{SCREEN_RECORDING_PREFIX + "%", mimeFilter};
            cursor = getContentResolver().query(collection, projection, selection, args,
                    MediaStore.MediaColumns.DATE_ADDED + " DESC");
            while (cursor != null && cursor.moveToNext()) {
                long id = cursor.getLong(0);
                long duration = cursor.getLong(2);
                long size = cursor.getLong(3);
                long added = cursor.getLong(4);
                int width = cursor.getInt(5);
                int height = cursor.getInt(6);
                String location = cursor.getString(7);
                out.add(new Recording(ContentUris.withAppendedId(collection, id),
                        cursor.getString(1), added, duration, size, width, height,
                        folderLabel(location, scoped)));
            }
        } catch (Exception ignored) {
        } finally {
            try { if (cursor != null) cursor.close(); } catch (Exception ignored) { }
        }
    }

    private void addRecordingRow(LinearLayout parent, final Recording item, int index) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(12), 0, dp(12));
        row.setClickable(true);
        row.setFocusable(true);

        final ImageView dot = new ImageView(this);
        LinearLayout.LayoutParams dotParams = new LinearLayout.LayoutParams(dp(26), dp(26));
        dotParams.setMargins(0, 0, dp(12), 0);
        row.addView(dot, dotParams);
        dot.setVisibility(recordingsSelectMode ? View.VISIBLE : View.GONE);
        styleSelectDot(dot, selectedRecordings.contains(item.uri.toString()));

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
        TextView nameView = label(item.displayName(), 14, TEXT, false);
        nameView.setMaxLines(1);
        nameView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        texts.addView(nameView, new LinearLayout.LayoutParams(MATCH, WRAP));

        TextView meta = label(item.dateLabel() + "  ·  " + item.meta(), 12, FAINT, false);
        texts.addView(meta, lp(MATCH, WRAP, 0, 3, 0, 0));

        // Where the video lives, so a changed save folder is easy to spot.
        TextView path = label(getString(R.string.recording_path, item.folder), 11, FAINT, false);
        path.setMaxLines(1);
        path.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        texts.addView(path, lp(MATCH, WRAP, 0, 2, 0, 0));

        parent.addView(row, lp(MATCH, WRAP, 0, 0, 0, 0));
        if (index < 8) {
            row.setAlpha(0f);
            row.setTranslationY(dp(8));
            row.animate().alpha(1f).translationY(0f)
                    .setStartDelay(index * 45L)
                    .setDuration(220)
                    .start();
        }
        row.setOnClickListener(v -> {
            if (recordingsSelectMode) {
                toggleSelection(item, dot);
            } else {
                playRecording(item);
            }
        });
        row.setOnLongClickListener(v -> {
            if (recordingsSelectMode) {
                toggleSelection(item, dot);
            } else {
                showRecordingActions(item);
            }
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

    /** Choosing videos to remove: taps select instead of opening. */
    private void setSelectMode(boolean on) {
        recordingsSelectMode = on;
        selectedRecordings.clear();
        updateSelectBar();
        // The rows themselves change (selection circles appear or go), so the
        // list is drawn again right away from the videos already read instead
        // of waiting for the next read to notice something.
        if (recordingsLoaded) renderRecordings(recordingsCache, true);
        loadRecordings();
    }

    private void toggleSelection(Recording item, ImageView dot) {
        String key = item.uri.toString();
        if (!selectedRecordings.remove(key)) selectedRecordings.add(key);
        styleSelectDot(dot, selectedRecordings.contains(key));
        updateSelectBar();
    }

    private void styleSelectDot(ImageView dot, boolean selected) {
        if (selected) {
            dot.setImageResource(R.drawable.ic_check);
            dot.setColorFilter(Color.WHITE);
            dot.setPadding(dp(5), dp(5), dp(5), dp(5));
        } else {
            dot.setImageDrawable(null);
        }
        GradientDrawable background = new GradientDrawable();
        background.setShape(GradientDrawable.OVAL);
        if (selected) {
            background.setColor(RED);
        } else {
            background.setColor(Color.TRANSPARENT);
            background.setStroke(dp(2), BORDER);
        }
        dot.setBackground(background);
    }

    private void updateSelectBar() {
        if (selectButton == null) return;
        int count = selectedRecordings.size();
        setButtonText(selectButton, getString(recordingsSelectMode
                ? R.string.dialog_cancel : R.string.select_recordings),
                recordingsSelectMode ? R.drawable.ic_close : R.drawable.ic_check,
                TEXT);
        deleteSelectedButton.setVisibility(
                recordingsSelectMode && count > 0 ? View.VISIBLE : View.GONE);
        if (count > 0) {
            setButtonText(deleteSelectedButton,
                    getString(R.string.delete_selected, count), R.drawable.ic_trash, RED);
        }
        if (recordingsSelectMode) {
            recordingsSummary.setText(getString(R.string.selected_count, count));
        }
    }

    /** A small icon in front of a text button, tinted like its text. */
    private void setButtonText(TextView view, CharSequence text, int iconRes, int color) {
        view.setText(text);
        android.graphics.drawable.Drawable icon =
                getResources().getDrawable(iconRes, getTheme()).mutate();
        icon.setColorFilter(color, android.graphics.PorterDuff.Mode.SRC_IN);
        icon.setBounds(0, 0, dp(14), dp(14));
        view.setCompoundDrawables(icon, null, null, null);
        view.setCompoundDrawablePadding(dp(6));
    }

    private void confirmDeleteSelected() {
        final List<Uri> uris = new ArrayList<>();
        for (String key : selectedRecordings) uris.add(Uri.parse(key));
        if (uris.isEmpty()) return;
        showCard(getString(R.string.delete_selected_title, uris.size()),
                getString(R.string.delete_selected_body),
                getString(R.string.dialog_delete),
                () -> {
                    for (Uri uri : uris) {
                        try {
                            getContentResolver().delete(uri, null, null);
                        } catch (Exception ignored) { }
                    }
                    selectedRecordings.clear();
                    recordingsSelectMode = false;
                    updateSelectBar();
                    // The videos are gone, so the list has to be read again and
                    // drawn from scratch rather than from the cached list.
                    recordingsSignature = "";
                    queryRecordings();
                },
                getString(R.string.dialog_cancel), null);
    }

    private void showRecordingActions(final Recording item) {
        String[] items = {
                getString(R.string.action_open),
                getString(R.string.action_share),
                getString(R.string.action_rename),
                getString(R.string.action_delete)
        };
        showChoices(item.displayName(), items, -1, which -> {
            if (which == 0) playRecording(item);
            else if (which == 1) shareRecording(item.uri);
            else if (which == 2) renameRecording(item);
            else confirmDelete(item);
        });
    }

    /** Renaming keeps the "DenRecorder_" prefix, which is how the list finds videos. */
    private void renameRecording(final Recording item) {
        final EditText input = new EditText(this);
        input.setText(item.displayName());
        input.setHint(getString(R.string.rename_hint));
        input.setTextColor(TEXT);
        input.setHintTextColor(FAINT);
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        input.setBackground(round(NAV_ACTIVE, 10, BORDER));
        input.setPadding(dp(14), dp(12), dp(14), dp(12));
        input.setSingleLine(true);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setSelection(input.getText().length());

        showCustomCard(getString(R.string.rename_title), input,
                getString(R.string.dialog_save),
                () -> {
                    String value = input.getText().toString().trim();
                    value = value.replace('/', '-').replace(':', '-').replace('\\', '-');
                    if (value.isEmpty() || value.contains("..")) {
                        Toast.makeText(this, R.string.rename_invalid, Toast.LENGTH_SHORT).show();
                        return false;
                    }
                    String fileName = value.startsWith(SCREEN_RECORDING_PREFIX)
                            ? value : SCREEN_RECORDING_PREFIX + value;
                    if (!fileName.toLowerCase(Locale.US).endsWith(".mp4")) fileName += ".mp4";
                    android.content.ContentValues values = new android.content.ContentValues();
                    values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
                    boolean ok = false;
                    try {
                        ok = getContentResolver().update(item.uri, values, null, null) > 0;
                    } catch (Exception ignored) { }
                    if (!ok) {
                        Toast.makeText(this, R.string.rename_failed, Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(this, R.string.rename_done, Toast.LENGTH_SHORT).show();
                    }
                    loadRecordings();
                    return true;
                },
                getString(R.string.dialog_cancel), null);
    }

    private void confirmDelete(final Recording item) {
        showCard(getString(R.string.delete_title),
                getString(R.string.delete_body, item.displayName()),
                getString(R.string.dialog_delete),
                () -> {
                    try {
                        getContentResolver().delete(item.uri, null, null);
                    } catch (Exception ignored) { }
                    loadRecordings();
                },
                getString(R.string.dialog_cancel), null);
    }

    private void playRecording(Recording item) {
        try {
            PlayerActivity.open(this, item.uri, item.displayName());
        } catch (Exception e) {
            status(R.string.player_open_failed, RED);
        }
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

    /** "Download/DenRecorder" out of a RELATIVE_PATH or a full file path. */
    private static String folderLabel(String location, boolean scoped) {
        if (location == null) return "";
        String out = location;
        if (!scoped) {
            int slash = out.lastIndexOf('/');
            out = slash > 0 ? out.substring(0, slash) : out;
            int root = out.indexOf("0/"); // /storage/emulated/0/Download
            if (root >= 0) out = out.substring(root + 2);
        }
        while (out.endsWith("/")) out = out.substring(0, out.length() - 1);
        return out.isEmpty() ? "/" : out;
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
        final String folder;

        Recording(Uri uri, String name, long added, long duration, long size,
                  int width, int height, String folder) {
            this.uri = uri;
            this.name = name == null ? "" : name;
            this.added = added;
            this.duration = duration;
            this.size = size;
            this.width = width;
            this.height = height;
            this.folder = folder == null ? "" : folder;
        }

        /** The file name the user sees and can rename, without prefix or ".mp4". */
        String displayName() {
            String out = name;
            if (out.toLowerCase(Locale.US).endsWith(".mp4")) {
                out = out.substring(0, out.length() - 4);
            }
            if (out.startsWith(SCREEN_RECORDING_PREFIX)) {
                out = out.substring(SCREEN_RECORDING_PREFIX.length());
            }
            return out.isEmpty() ? name : out;
        }

        String dateLabel() {
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
        // The two other screens are built on their first visit.
        if (index == 1 && settingsList == null) buildSettings();
        else if (index == 2 && recordingsPageList == null) buildRecordings();
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
        // Settings and the recordings are built at start, so they get their
        // rise-in the first time they are actually opened.
        if (index == 1 && !settingsRevealed) {
            settingsRevealed = true;
            revealChildren(settingsList, 32);
        } else if (index == 2 && !recordingsRevealed) {
            recordingsRevealed = true;
            revealChildren(recordingsPageList, 45);
        }
        updateBackCallback();
        if (index == 1) {
            updateQualityValues();
            updateAudioValue();
            updateBatteryValue();
            updateTileValue();
            updateFastStartValue();
            updateLanguageValue();
            updateThemeValue();
            updateIslandValue();
            updateStorageValue();
            updateOrientationValue();
            updateDrawValue();
            updateDrawSizeValue();
            updateDrawModeValue();
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

    /** Parts of a screen rise in one after another, like a short cascade. */
    private void revealChildren(LinearLayout list, int stepMs) {
        if (list == null) return;
        for (int i = 0; i < list.getChildCount(); i++) {
            View child = list.getChildAt(i);
            child.animate().cancel();
            child.setAlpha(0f);
            child.setTranslationY(dp(10));
            child.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setStartDelay(i * (long) stepMs)
                    .setDuration(260)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator())
                    .start();
        }
    }

    /** A settings value fades to its new text instead of switching abruptly. */
    private void setValue(TextView view, CharSequence text) {
        if (view == null || text == null) return;
        CharSequence current = view.getText();
        if (current != null && text.toString().contentEquals(current)) return;
        view.animate().cancel();
        final CharSequence next = text;
        view.animate().alpha(0f).setDuration(70).withEndAction(new Runnable() {
            @Override
            public void run() {
                view.setText(next);
                view.animate().alpha(1f).setDuration(150).start();
            }
        }).start();
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
        icon.animate().cancel();
        label.animate().cancel();
        if (active) {
            // The picked tab's icon pops once, and its label fades in.
            icon.setScaleX(1.22f);
            icon.setScaleY(1.22f);
            icon.animate().scaleX(1f).scaleY(1f).setDuration(260)
                    .setInterpolator(new android.view.animation.OvershootInterpolator(2.4f))
                    .start();
            label.setAlpha(0.35f);
            label.animate().alpha(1f).setDuration(190).start();
        } else {
            icon.setScaleX(1f);
            icon.setScaleY(1f);
            label.setAlpha(1f);
        }
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
        // Long values (Vietnamese "Mượt · giới hạn 60 fps", folder paths) wrap
        // instead of being cut off.
        valueView.setMaxLines(2);
        valueView.setEllipsize(null);
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

    /** Start and stop keep the same button, with the icon that fits it. */
    private void setMainButton(boolean recording) {
        if (mainButton == null) return;
        boolean changed = mainButton.isSelected() != recording;
        mainButton.setSelected(recording);
        int background = recording ? RED : PRIMARY;
        int foreground = recording ? Color.WHITE : PRIMARY_FG;
        mainButton.setBackground(round(background, 12, 0));
        mainLabel.setText(recording ? R.string.stop_recording : R.string.start_recording);
        mainLabel.setTextColor(foreground);
        mainIcon.setImageResource(recording ? R.drawable.ic_stop : R.drawable.ic_record);
        mainIcon.setColorFilter(foreground);
        if (!changed) return;
        // Start and stop get a short pulse, so the switch is felt as well as seen.
        mainButton.animate().cancel();
        mainButton.setScaleX(0.97f);
        mainButton.setScaleY(0.97f);
        mainButton.animate().scaleX(1f).scaleY(1f).setDuration(240)
                .setInterpolator(new android.view.animation.OvershootInterpolator(2f))
                .start();
        mainIcon.setScaleX(0.5f);
        mainIcon.setScaleY(0.5f);
        mainIcon.setAlpha(0.3f);
        mainIcon.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(220)
                .setInterpolator(new android.view.animation.OvershootInterpolator(2.2f))
                .start();
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
        // Same limit the recorder uses, so the label matches the real video.
        return ScreenRecorder.limitToPlayable(outWidth, outHeight);
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
        // The chosen area is what the video holds, whatever the resolution is.
        String size = prefs.getBoolean("regionOn", false)
                ? getString(R.string.value_region_custom, regionSize()[0], regionSize()[1])
                : dims(resWidth);
        return size + " · " + effectiveFps() + " fps · "
                + (hevc() ? "H.265" : "H.264") + " · " + bitrateMbps + " Mbps";
    }

    private void updateQualityValues() {
        setValue(resolutionValueView, resolutionLabel());
        updateRegionValue();
        setValue(bitrateValueView, getString(R.string.value_mbps, bitrateMbps));
        setValue(fpsValueView, getString(R.string.value_fps, videoFps));
        setValue(codecValueView, codecLabel());
        setValue(performanceValueView, getString(perfMode
                ? R.string.performance_on
                : R.string.performance_off));
        setValue(heroBody, summaryLine());
    }

    private void updateAudioValue() {
        if (audioValueView == null) return;
        int res;
        if (audioSource == ScreenRecorder.AUDIO_MIC) res = R.string.audio_mic;
        else if (audioSource == ScreenRecorder.AUDIO_INTERNAL) res = R.string.audio_internal;
        else res = R.string.audio_none;
        setValue(audioValueView, getString(res));
    }

    private void updateLanguageValue() {
        if (languageValueView == null) return;
        setValue(languageValueView, getString(
                "vi".equals(language) ? R.string.language_vi : R.string.language_en));
    }

    private void updateTileValue() {
        if (tileValueView == null) return;
        setValue(tileValueView, getString(prefs.getBoolean("tileAdded", false)
                ? R.string.value_tile_added
                : R.string.value_tile_missing));
    }

    private void updateFastStartValue() {
        if (fastStartValueView == null) return;
        int res;
        if (!FastStart.enabled(this)) res = R.string.value_fast_off;
        else if (FastStart.available()) res = R.string.value_fast_on;
        else res = R.string.value_fast_need;
        setValue(fastStartValueView, getString(res));
    }

    /** One tap starts a recording: Shizuku presses Android's own button. */
    private void toggleFastStart() {
        if (FastStart.enabled(this)) {
            FastStart.setEnabled(this, false);
            updateFastStartValue();
            Toast.makeText(this, R.string.fast_start_off_toast, Toast.LENGTH_SHORT).show();
            return;
        }
        showCard(getString(R.string.fast_start_title), getString(R.string.fast_start_body),
                getString(R.string.fast_start_enable), this::enableFastStart,
                getString(R.string.dialog_cancel), null);
    }

    private void enableFastStart() {
        if (!ShizukuBridge.installed(this)) {
            showCard(getString(R.string.fast_start_need_title),
                    getString(R.string.fast_start_need_body),
                    getString(R.string.island_install_shizuku), this::openShizukuDownload,
                    getString(R.string.dialog_cancel), null);
            return;
        }
        if (!ShizukuBridge.running()) {
            showCard(getString(R.string.fast_start_need_title),
                    getString(R.string.island_waiting_shizuku),
                    getString(R.string.island_open_shizuku), this::openShizukuApp,
                    getString(R.string.dialog_cancel), null);
            return;
        }
        if (!ShizukuBridge.allowed()) {
            fastStartRequestPending = true;
            Toast.makeText(this, R.string.island_allow_in_shizuku, Toast.LENGTH_LONG).show();
            ShizukuBridge.requestPermission(REQ_SHIZUKU_FAST);
            return;
        }
        FastStart.setEnabled(this, true);
        updateFastStartValue();
        Toast.makeText(this, R.string.fast_start_on_toast, Toast.LENGTH_SHORT).show();
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
        setValue(batteryValueView, getString(batteryUnrestricted()
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

    /** A whole-screen recording or just a chosen part of the screen. */
    private void chooseRegion() {
        if (blockWhileRecording()) return;
        boolean on = prefs.getBoolean("regionOn", false);
        String[] items = {
                getString(R.string.value_region_full),
                getString(R.string.region_pick)
        };
        showChoices(getString(R.string.title_region), items, on ? 1 : 0, which -> {
            if (which == 0) {
                prefs.edit().putBoolean("regionOn", false).apply();
                updateRegionValue();
                // Without a frame the floating record button has nothing to do.
                RegionPickerService.stop(this);
                return;
            }
            if (!RegionPickerService.canDraw(this)) {
                pendingRegionPick = true;
                status(R.string.region_need_overlay, MUTED);
                requestOverlayPermission(REQ_REGION_OVERLAY);
                return;
            }
            RegionPickerService.start(this);
            status(R.string.region_hint, MUTED);
        });
    }

    /**
     * The chosen area in pixels. It is stored as parts of the screen, so the
     * same frame is meant after a rotation.
     */
    private int[] regionSize() {
        int[] size = nativeSize();
        float width = prefs.getFloat("regionW", 1f);
        float height = prefs.getFloat("regionH", 1f);
        return new int[]{
                Math.max(2, Math.round(size[0] * width) & ~1),
                Math.max(2, Math.round(size[1] * height) & ~1)
        };
    }

    private void updateRegionValue() {
        if (regionValueView == null) return;
        if (!prefs.getBoolean("regionOn", false)) {
            setValue(regionValueView, getString(R.string.value_region_full));
            return;
        }
        int[] size = regionSize();
        setValue(regionValueView, getString(R.string.value_region_custom, size[0], size[1]));
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
        final String[] items = new String[presets.length + 2];
        int checked = -1;
        for (int i = 0; i < presets.length; i++) {
            items[i] = presets[i];
            if (presets[i].equals(saveFolder())) checked = i;
        }
        items[presets.length] = getString(R.string.folder_browse);
        items[presets.length + 1] = getString(R.string.folder_custom);
        showChoices(getString(R.string.title_folder), items, checked, which -> {
            if (which < presets.length) {
                applySaveFolder(presets[which]);
            } else if (which == presets.length) {
                openFolderPicker();
            } else {
                showFolderInput();
            }
        });
    }

    /** The phone's own folder browser, so no folder has to be typed by hand. */
    private void openFolderPicker() {
        try {
            Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                            | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                            | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            startActivityForResult(pick, REQ_FOLDER);
        } catch (Exception e) {
            status(R.string.folder_browse_unavailable, RED);
        }
    }

    /**
     * Turns the picked folder into the relative path MediaStore writes to.
     * Only the phone's own storage has one; a card or a cloud folder has not.
     */
    private String folderFromTree(Uri tree) {
        try {
            String docId = android.provider.DocumentsContract.getTreeDocumentId(tree);
            if (docId == null) return null;
            int colon = docId.indexOf(':');
            String root = colon < 0 ? docId : docId.substring(0, colon);
            String path = colon < 0 ? "" : docId.substring(colon + 1);
            if (!"primary".equalsIgnoreCase(root)) return null;
            path = path.replaceAll("^/+", "").replaceAll("/+$", "");
            return path.isEmpty() ? null : path;
        } catch (Exception e) {
            return null;
        }
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
        setValue(storageValueView, saveFolder());
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
            if (!"auto".equals(orientation) && !RotationLock.usable(this)) {
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
        setValue(orientationValueView, getString(res));
    }

    // ---------- Drawing on screen ----------

    /**
     * The floating button with rectangle, circle, pause and stop. Android needs
     * the "display over other apps" grant before anything can be drawn.
     */
    private void toggleDrawOverlay() {
        drawOverlay = !drawOverlay;
        prefs.edit().putBoolean("drawOverlay", drawOverlay).apply();
        updateDrawValue();
        if (drawOverlay && !DrawOverlayService.canDraw(this)) {
            showCard(getString(R.string.draw_permission_title),
                    getString(R.string.draw_permission_body),
                    getString(R.string.draw_permission_grant),
                    this::requestOverlayPermission,
                    getString(R.string.draw_permission_later), null);
        }
    }

    private String[] drawSizeLabels() {
        return new String[]{
                getString(R.string.draw_size_small),
                getString(R.string.draw_size_medium),
                getString(R.string.draw_size_large),
                getString(R.string.draw_size_huge)
        };
    }

    private void chooseDrawSize() {
        String[] sizes = drawSizeLabels();
        showChoices(getString(R.string.title_draw_size), sizes,
                Math.max(0, Math.min(sizes.length - 1, drawSize)), which -> {
                    drawSize = which;
                    prefs.edit().putInt("drawSize", drawSize).apply();
                    updateDrawSizeValue();
                    DrawOverlayService.refreshSize(this);
                });
    }

    private void updateDrawSizeValue() {
        if (drawSizeValueView == null) return;
        String[] sizes = drawSizeLabels();
        setValue(drawSizeValueView, sizes[Math.max(0, Math.min(sizes.length - 1, drawSize))]);
    }

    /** Always in sight, or faded away while nobody uses it. */
    private void chooseDrawMode() {
        String[] modes = {
                getString(R.string.draw_mode_always),
                getString(R.string.draw_mode_fade)
        };
        showChoices(getString(R.string.title_draw_mode), modes, drawAutoHide ? 1 : 0, which -> {
            drawAutoHide = which == 1;
            prefs.edit().putBoolean("drawAutoHide", drawAutoHide).apply();
            updateDrawModeValue();
            DrawOverlayService.refreshMode(this);
        });
    }

    private void updateDrawModeValue() {
        if (drawModeValueView == null) return;
        setValue(drawModeValueView, getString(drawAutoHide
                ? R.string.draw_mode_fade : R.string.draw_mode_always));
    }

    private void requestOverlayPermission() {
        requestOverlayPermission(REQ_OVERLAY);
    }

    private void requestOverlayPermission(int requestCode) {
        try {
            startActivityForResult(new Intent(
                    android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())), requestCode);
        } catch (Exception e) {
            status(R.string.draw_permission_unavailable, RED);
        }
    }

    private void updateDrawValue() {
        if (drawValueView == null) return;
        int res = !drawOverlay ? R.string.value_draw_off
                : DrawOverlayService.canDraw(this) ? R.string.value_draw_on
                : R.string.value_draw_need;
        setValue(drawValueView, getString(res));
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

    private void chooseTheme() {
        final String[] values = {"dark", "light", "auto"};
        final String[] names = {
                getString(R.string.theme_dark),
                getString(R.string.theme_light),
                getString(R.string.theme_auto)
        };
        int checked = 0;
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(theme)) checked = i;
        }
        showChoices(getString(R.string.title_theme), names, checked, which -> {
            theme = values[which];
            // Come back to the settings screen after the new palette is applied.
            prefs.edit().putString("theme", theme)
                    .putBoolean("openSettingsNext", true).apply();
            recreate();
        });
    }

    // ---------- Super Island (beta) ----------

    /** Shizuku answers here after the user allows DenRecorder in its dialog. */
    private final rikka.shizuku.Shizuku.OnRequestPermissionResultListener islandPermissionListener =
            new rikka.shizuku.Shizuku.OnRequestPermissionResultListener() {
                @Override
                public void onRequestPermissionResult(int requestCode, int grantResult) {
                    if (requestCode == REQ_SHIZUKU_FAST) {
                        if (!fastStartRequestPending) return;
                        fastStartRequestPending = false;
                        handler.post(new Runnable() {
                            @Override
                            public void run() {
                                if (grantResult == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                                    enableFastStart();
                                } else {
                                    showCard(getString(R.string.fast_start_need_title),
                                            getString(R.string.fast_start_denied_body),
                                            getString(R.string.island_request_again),
                                            MainActivity.this::enableFastStart,
                                            getString(R.string.dialog_cancel), null);
                                }
                            }
                        });
                        return;
                    }
                    if (requestCode != REQ_SHIZUKU || !islandRequestPending) return;
                    islandRequestPending = false;
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (grantResult == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                                grantIslandPermission();
                            } else {
                                showCard(getString(R.string.island_failed_title),
                                        getString(R.string.island_denied_body),
                                        getString(R.string.island_request_again),
                                        MainActivity.this::askShizukuPermission,
                                        getString(R.string.dialog_cancel), null);
                            }
                        }
                    });
                }
            };

    private void chooseIsland() {
        final boolean on = Island.enabled(this);
        String body = getString(R.string.island_body);
        if (on) body = getString(R.string.island_body_on);
        showCard(getString(R.string.island_title), body,
                getString(on ? R.string.island_disable : R.string.island_enable),
                on ? this::disableIsland : this::askShizukuPermission,
                getString(R.string.dialog_cancel), null);
    }

    private void updateIslandValue() {
        if (islandValueView == null) return;
        int res;
        if (!Island.enabled(this)) res = R.string.island_value_off;
        else if (Island.allowed(this)) res = R.string.island_value_on;
        else res = R.string.island_value_need;
        setValue(islandValueView, getString(res));
        setValue(islandModeValueView, getString(Island.onlyWhenPulled(this)
                ? R.string.island_mode_pull : R.string.island_mode_always));
    }

    /** The island can wait for a pull of the status bar, or stay visible. */
    private void chooseIslandMode() {
        boolean always = !Island.onlyWhenPulled(this);
        showChoices(getString(R.string.title_island_mode),
                new String[]{getString(R.string.island_mode_pull),
                        getString(R.string.island_mode_always)},
                always ? 1 : 0, which -> {
                    Island.setAlwaysVisible(this, which == 1);
                    updateIslandValue();
                });
    }

    private void disableIsland() {
        Island.setEnabled(this, false);
        Island.hideTest(this);
        updateIslandValue();
        Toast.makeText(this, R.string.island_off_toast, Toast.LENGTH_SHORT).show();
    }

    /** Shizuku: installed, running, allowed, then the one permission, then a test. */
    private void askShizukuPermission() {
        if (!Island.isXiaomi()) {
            Toast.makeText(this, R.string.island_no_xiaomi, Toast.LENGTH_LONG).show();
            return;
        }
        if (!ShizukuBridge.installed(this)) {
            showCard(getString(R.string.island_need_shizuku_title),
                    getString(R.string.island_need_shizuku_body),
                    getString(R.string.island_install_shizuku), this::openShizukuDownload,
                    getString(R.string.dialog_cancel), null);
            return;
        }
        if (!ShizukuBridge.running()) {
            showCard(getString(R.string.island_need_shizuku_title),
                    getString(R.string.island_waiting_shizuku),
                    getString(R.string.island_open_shizuku), this::openShizukuApp,
                    getString(R.string.dialog_cancel), null);
            return;
        }
        if (ShizukuBridge.allowed()) {
            grantIslandPermission();
            return;
        }
        islandRequestPending = true;
        Toast.makeText(this, R.string.island_allow_in_shizuku, Toast.LENGTH_LONG).show();
        ShizukuBridge.requestPermission(REQ_SHIZUKU);
    }

    private void grantIslandPermission() {
        Toast.makeText(this, R.string.island_granting, Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                final String output = ShizukuBridge.grantIslandPermission(MainActivity.this);
                Island.forgetVerdict();
                final boolean ok = Island.allowed(MainActivity.this);
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (ok) {
                            Island.setEnabled(MainActivity.this, true);
                            updateIslandValue();
                            Island.showTest(MainActivity.this);
                            handler.postDelayed(new Runnable() {
                                @Override
                                public void run() {
                                    Island.hideTest(MainActivity.this);
                                }
                            }, 15_000);
                            showCard(getString(R.string.island_done_title),
                                    getString(R.string.island_done_body),
                                    getString(R.string.dialog_ok), null, null, null);
                        } else {
                            showCard(getString(R.string.island_failed_title),
                                    getString(R.string.island_failed_body, output),
                                    getString(R.string.island_request_again),
                                    MainActivity.this::askShizukuPermission,
                                    getString(R.string.dialog_cancel), null);
                        }
                    }
                });
            }
        }, "island-grant").start();
    }

    private void openShizukuApp() {
        Intent intent = getPackageManager().getLaunchIntentForPackage(ShizukuBridge.PACKAGE);
        if (intent == null) {
            openShizukuDownload();
            return;
        }
        try {
            startActivity(intent);
        } catch (Exception e) {
            openShizukuDownload();
        }
    }

    private void openShizukuDownload() {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://shizuku.rikka.app/download/")));
        } catch (Exception e) {
            Toast.makeText(this, R.string.island_no_browser, Toast.LENGTH_LONG).show();
        }
    }

    private void updateThemeValue() {
        if (themeValueView == null) return;
        int res = "light".equals(theme) ? R.string.theme_light
                : "dark".equals(theme) ? R.string.theme_dark
                : R.string.theme_auto;
        setValue(themeValueView, getString(res));
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
        if (!"auto".equals(orientation) && !RotationLock.usable(this)) {
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
            FastStart.arm(this);
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
        if (requestCode == REQ_REGION_OVERLAY) {
            if (pendingRegionPick) {
                pendingRegionPick = false;
                if (RegionPickerService.canDraw(this)) {
                    RegionPickerService.start(this);
                    status(R.string.region_hint, MUTED);
                } else {
                    status(R.string.region_need_overlay, RED);
                }
            }
            return;
        }
        if (requestCode == REQ_OVERLAY) {
            updateDrawValue();
            status(DrawOverlayService.canDraw(this)
                    ? R.string.draw_permission_on
                    : R.string.draw_permission_off, MUTED);
            return;
        }
        if (requestCode == REQ_FOLDER) {
            if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
            String folder = folderFromTree(data.getData());
            if (folder == null) {
                showCard(getString(R.string.folder_browse_unsupported_title),
                        getString(R.string.folder_browse_unsupported_body),
                        getString(R.string.dialog_ok), null, null, null);
                return;
            }
            applySaveFolder(folder);
            return;
        }
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
            // The service is starting right now: look again in a moment so the
            // screen switches to "recording" at once instead of waiting for the
            // slower idle poll.
            handler.removeCallbacks(poll);
            handler.postDelayed(poll, 200);
        } catch (Exception e) {
            status(getString(R.string.status_start_failed, String.valueOf(e.getMessage())), RED);
        }
    }

    private void status(String text, int color) {
        if (statusView == null) return;
        statusView.setVisibility(View.VISIBLE);
        boolean same = text != null && text.contentEquals(statusView.getText());
        statusView.setTextColor(color);
        if (same && statusView.getAlpha() == 1f) return;
        statusView.animate().cancel();
        statusView.setText(text);
        if (same) {
            statusView.setAlpha(1f);
            return;
        }
        statusView.setAlpha(0f);
        statusView.setTranslationY(dp(4));
        statusView.animate().alpha(1f).translationY(0f).setDuration(180).start();
    }

    // ---------- State refresh ----------

    private void updateUi() {
        boolean recording = RecorderService.isRecording;
        if (recording) {
            sawRecording = true;
            stopDetectedAt = 0;
            if (!uiRecording) {
                uiRecording = true;
                setMainButton(true);
                startDotAnimation();
            }
            long elapsed = RecorderService.elapsedMs();
            String title = getString(RecorderService.isPaused
                    ? R.string.hero_paused_title
                    : R.string.hero_recording_title)
                    + "  " + formatDuration(elapsed);
            // Setting the same text again would re-lay out the row for nothing.
            if (!title.contentEquals(heroTitle.getText())) heroTitle.setText(title);
            statusView.setVisibility(View.GONE);
        } else {
            if (uiRecording) {
                uiRecording = false;
                setMainButton(false);
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
