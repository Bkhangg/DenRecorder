package com.bkggds.denrecorder;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Xiaomi HyperOS Super Island (超级岛), beta.
 *
 * HyperOS picks an island entry up from the extras of an ongoing notification:
 * a JSON template in "miui.focus.param", the pictures in "miui.focus.pics" and
 * the buttons in "miui.focus.actions". SystemUI only reads them from apps it
 * allows, so the permission miui.permission.USE_FOCUS_NOTIFICATION has to be
 * granted by Shizuku first. Everything here is best effort: when the phone does
 * not know the island the notification simply stays a normal notification.
 */
public final class Island {

    private static final String TAG = "Island";

    /** SharedPreferences entry: the user switched the beta on. */
    public static final String KEY = "islandBeta";
    /** SharedPreferences entry: keep the island out of the status bar. */
    public static final String KEY_MODE = "islandMode";
    public static final String CHANNEL_TEST = "island_test";
    public static final int TEST_ID = 4713;

    private static final String EXTRA_PARAM = "miui.focus.param";
    private static final String EXTRA_PICS = "miui.focus.pics";
    private static final String EXTRA_ACTIONS = "miui.focus.actions";
    private static final String ACTION_KEY = "miui.focus.action_";
    private static final String PIC_SMALL = "miui.focus.pic_small";
    private static final String PIC_SMALL_DARK = "miui.focus.pic_small_dark";
    private static final String PIC_APP_ICON = "miui.focus.pic_app_icon";
    private static final String PIC_APP_ICON_DARK = "miui.focus.pic_app_icon_dark";
    private static final String PUBLIC_PROVIDER = "content://miui.statusbar.notification.public";
    private static final String BUSINESS = "denrecorder_recording";

    /** The island drops an entry whose sequence did not grow. */
    private static long lastSequence = System.currentTimeMillis() / 1000L;

    /** canShowFocus is an IPC into SystemUI: ask it at most once a minute. */
    private static long allowedAt;
    private static boolean allowedValue;

    private Island() { }

    public static boolean isXiaomi() {
        String brand = Build.BRAND == null ? "" : Build.BRAND.toLowerCase();
        String maker = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER.toLowerCase();
        return brand.contains("xiaomi") || brand.contains("redmi") || brand.contains("poco")
                || maker.contains("xiaomi");
    }

    /** 1 = HyperOS 1, 2 = HyperOS 2, 3 = HyperOS 3 (Super Island), 0 = no island. */
    public static int protocol(Context context) {
        try {
            return Settings.System.getInt(context.getContentResolver(),
                    "notification_focus_protocol", 0);
        } catch (Throwable e) {
            return 0;
        }
    }

    /** persist.sys.feature.island, the ROM's own "there is an island" flag. */
    public static boolean islandProperty() {
        try {
            Class<?> clazz = Class.forName("android.os.SystemProperties");
            Object value = clazz.getMethod("getBoolean", String.class, boolean.class)
                    .invoke(null, "persist.sys.feature.island", false);
            return Boolean.TRUE.equals(value);
        } catch (Throwable e) {
            return false;
        }
    }

    /** True when this phone has an island at all (not necessarily for us). */
    public static boolean supported(Context context) {
        return isXiaomi() && (protocol(context) > 0 || islandProperty());
    }

    /** SystemUI's verdict: may this app draw in the island? */
    public static boolean allowed(Context context) {
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - allowedAt < 60_000L) return allowedValue;
        boolean allowed = false;
        try {
            Bundle extras = new Bundle();
            extras.putString("package", context.getPackageName());
            Bundle reply = context.getContentResolver()
                    .call(Uri.parse(PUBLIC_PROVIDER), "canShowFocus", null, extras);
            allowed = reply != null && reply.getBoolean("canShowFocus", false);
        } catch (Throwable e) {
            allowed = false;
        }
        allowedValue = allowed;
        allowedAt = now;
        return allowed;
    }

    /** Called after Shizuku granted the permission, so the result is fresh. */
    public static void forgetVerdict() {
        allowedAt = 0;
    }

    /** The beta is on and this phone can still show it. */
    public static boolean enabled(Context context) {
        return prefs(context).getBoolean(KEY, false) && isXiaomi();
    }

    public static void setEnabled(Context context, boolean on) {
        prefs(context).edit().putBoolean(KEY, on).apply();
    }

    /**
     * True when the island waits for a pull of the status bar instead of sitting
     * in the status bar all the time. This is the default, as the island is
     * meant to be a quiet extra, not a permanent badge.
     */
    public static boolean onlyWhenPulled(Context context) {
        return !"always".equals(prefs(context).getString(KEY_MODE, "swipe"));
    }

    public static void setAlwaysVisible(Context context, boolean always) {
        prefs(context).edit().putString(KEY_MODE, always ? "always" : "swipe").apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(LocaleHelper.PREFS, Context.MODE_PRIVATE);
    }

    private static Icon smallIcon(Context context) {
        return Icon.createWithResource(context, R.drawable.ic_video);
    }

    /**
     * Adds the island template to an ongoing recording notification.
     *
     * The island is a compact pill with the app icon and a timer HyperOS counts
     * by itself; tapping it opens the expanded entry with the pause/resume and
     * stop buttons that the notification already carries.
     *
     * @param startedAt the moment the timer counts up from, in epoch milliseconds.
     */
    public static void apply(Context context, Notification notification, String state,
                             String time, long startedAt, boolean paused) {
        apply(context, notification, state, time, startedAt, paused, false);
    }

    /**
     * @param forceVisible true only for the test entry, which should show up
     *                     right away instead of waiting for a pull.
     */
    public static void apply(Context context, Notification notification, String state,
                             String time, long startedAt, boolean paused,
                             boolean forceVisible) {
        if (notification == null) return;
        try {
            JSONObject param = buildParam(context, notification, state, time, startedAt,
                    paused, forceVisible);
            notification.extras.putString(EXTRA_PARAM, param.toString());

            Bundle pics = new Bundle();
            Icon icon = Icon.createWithResource(context, R.mipmap.ic_launcher);
            pics.putParcelable(PIC_APP_ICON, icon);
            pics.putParcelable(PIC_APP_ICON_DARK, icon);
            pics.putParcelable(PIC_SMALL, smallIcon(context));
            pics.putParcelable(PIC_SMALL_DARK, smallIcon(context));
            notification.extras.putBundle(EXTRA_PICS, pics);

        } catch (Throwable e) {
            Log.w(TAG, "island extras failed", e);
        }
    }

    /**
     * HyperOS reads buttons from a bundle of Notification.Action objects, and
     * the template only names them by key. The recording notification already
     * has pause/resume and stop, so the island shows the very same buttons.
     */
    private static void putActions(Notification notification, JSONObject params)
            throws Exception {
        Notification.Action[] actions = notification.actions;
        if (actions == null || actions.length == 0) return;

        Bundle bundle = new Bundle();
        JSONArray list = new JSONArray();
        int count = Math.min(3, actions.length);
        for (int i = 0; i < count; i++) {
            String key = ACTION_KEY + (i + 1);
            bundle.putParcelable(key, actions[i]);
            list.put(new JSONObject().put("action", key));
        }
        notification.extras.putBundle(EXTRA_ACTIONS, bundle);
        params.put("actions", list);
    }

    private static JSONObject buildParam(Context context, Notification notification,
                                         String state, String time, long startedAt,
                                         boolean paused, boolean forceVisible) throws Exception {
        long sequence = Math.max(System.currentTimeMillis() / 1000L, lastSequence + 1);
        lastSequence = sequence;
        String app = context.getString(R.string.app_name);
        String title = state;

        JSONObject baseInfo = new JSONObject()
                .put("type", 2)
                .put("title", title)
                .put("content", app)
                .put("subTitle", "")
                .put("extraTitle", "")
                .put("specialTitle", "")
                .put("subContent", "")
                .put("showDivider", true)
                .put("showContentDivider", false)
                .put("colorTitle", "#111111")
                .put("colorTitleDark", "#ffffff")
                .put("colorContent", "#333333")
                .put("colorContentDark", "#cccccc");

        // The compact summary is only the icon and the timer. HyperOS has a
        // system chronometer (sameWidthDigitInfo with timerInfo), but it only
        // ticks for focus notifications, not for an island an app posts itself:
        // it stayed at 00:00 here, so the text is refreshed once a second.
        JSONObject islandArea = new JSONObject()
                .put("imageTextInfoLeft", new JSONObject()
                        .put("type", 1)
                        .put("picInfo", new JSONObject().put("type", 1).put("pic", PIC_SMALL)));
        islandArea.put("sameWidthDigitInfo", new JSONObject()
                .put("content", "")
                .put("digit", time)
                .put("showHighlightColor", false)
                .put("turnAnim", false));

        boolean pulled = !forceVisible && onlyWhenPulled(context);
        JSONObject island = new JSONObject()
                // 2 = the island exists to be operated, so a tap opens the
                // entry with its buttons instead of just opening the app.
                .put("islandProperty", 2)
                .put("islandPriority", 2)
                .put("islandTimeout", 24 * 60 * 60)
                // Keep the summary away until the status bar is pulled down.
                .put("dismissIsland", pulled)
                .put("maxSize", false)
                .put("bigIslandArea", islandArea)
                .put("smallIslandArea", new JSONObject()
                        .put("picInfo", new JSONObject().put("type", 1).put("pic", PIC_SMALL)));

        JSONObject params = new JSONObject()
                .put("protocol", 1)
                .put("business", BUSINESS)
                .put("orderId", BUSINESS)
                .put("updatable", true)
                .put("enableFloat", false)
                .put("islandFirstFloat", false)
                .put("reopen", "close")
                .put("outEffectSrc", "")
                .put("sequence", sequence)
                .put("ticker", title + " · " + time)
                .put("aodTitle", state)
                .put("baseInfo", baseInfo)
                .put("picInfo", new JSONObject().put("type", 1).put("pic", PIC_APP_ICON))
                // No hintInfo: HyperOS draws it as a second line under baseInfo,
                // which only repeated the app name and the timer.
                .put("param_island", island);
        putActions(notification, params);

        return new JSONObject().put("param_v2", params);
    }

    /**
     * A short, harmless island entry on the record screen, so the user can see
     * whether the permission really took, without recording anything.
     */
    public static void showTest(Context context) {
        try {
            NotificationManager nm =
                    (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationChannel channel = new NotificationChannel(CHANNEL_TEST,
                        context.getString(R.string.island_channel), NotificationManager.IMPORTANCE_LOW);
                channel.setShowBadge(false);
                channel.setSound(null, null);
                nm.createNotificationChannel(channel);
            }
            Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                    ? new Notification.Builder(context, CHANNEL_TEST)
                    : new Notification.Builder(context);
            builder.setSmallIcon(R.drawable.ic_record)
                    .setContentTitle(context.getString(R.string.island_test_title))
                    .setContentText(context.getString(R.string.island_test_text))
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .setShowWhen(false)
                    .setCategory(Notification.CATEGORY_STATUS)
                    .setPriority(Notification.PRIORITY_LOW);
            Notification notification = builder.build();
            // The test shows at once, even in "only when pulled down" mode.
            apply(context, notification,
                    context.getString(R.string.island_test_title), "00:10",
                    System.currentTimeMillis(), false, true);
            nm.notify(TEST_ID, notification);
        } catch (Throwable e) {
            Log.w(TAG, "test island failed", e);
        }
    }

    public static void hideTest(Context context) {
        try {
            NotificationManager nm =
                    (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            nm.cancel(TEST_ID);
        } catch (Throwable ignored) { }
    }
}
