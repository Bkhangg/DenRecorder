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

import org.json.JSONObject;

/**
 * Xiaomi HyperOS Super Island (超级岛), beta.
 *
 * HyperOS picks an island entry up from the extras of an ongoing notification:
 * a JSON template in "miui.focus.param" and the pictures in "miui.focus.pics".
 * SystemUI only reads them from apps it allows, so the permission
 * miui.permission.USE_FOCUS_NOTIFICATION has to be granted by Shizuku first.
 * Everything here is best effort: when the phone does not know the island the
 * notification simply stays a normal notification.
 */
public final class Island {

    private static final String TAG = "Island";

    /** SharedPreferences entry: the user switched the beta on. */
    public static final String KEY = "islandBeta";
    public static final String CHANNEL_TEST = "island_test";
    public static final int TEST_ID = 4713;

    private static final String EXTRA_PARAM = "miui.focus.param";
    private static final String EXTRA_PICS = "miui.focus.pics";
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

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(LocaleHelper.PREFS, Context.MODE_PRIVATE);
    }

    private static Icon smallIcon(Context context) {
        return Icon.createWithResource(context, R.drawable.ic_video);
    }

    /** Adds the island template to an ongoing recording notification. */
    public static void apply(Context context, Notification notification, String state,
                             String time, long startedAt, boolean paused) {
        if (notification == null) return;
        try {
            JSONObject param = buildParam(context, state, time, startedAt, paused);
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

    private static JSONObject buildParam(Context context, String state, String time,
                                        long startedAt, boolean paused) throws Exception {
        long sequence = Math.max(System.currentTimeMillis() / 1000L, lastSequence + 1);
        lastSequence = sequence;
        String app = context.getString(R.string.app_name);
        String title = state;

        JSONObject baseInfo = new JSONObject()
                .put("type", 2)
                .put("title", title)
                .put("content", app + " · " + time)
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

        JSONObject hintInfo = new JSONObject()
                .put("type", 2)
                .put("content", app)
                .put("title", time)
                .put("timerInfo", new JSONObject()
                        .put("timerType", paused ? 1 : 0)
                        .put("timerWhen", paused ? 0 : startedAt)
                        .put("timerTotal", 0)
                        .put("timerSystemCurrent", System.currentTimeMillis()))
                .put("colorContent", "#666666")
                .put("colorContentDark", "#aaaaaa")
                .put("colorTitle", "#222222")
                .put("colorTitleDark", "#eeeeee");

        JSONObject textInfo = new JSONObject()
                .put("frontTitle", "")
                .put("title", time)
                .put("content", "")
                .put("showHighlightColor", false)
                .put("narrowFont", false);

        JSONObject islandArea = new JSONObject()
                .put("templateNo", 2)
                .put("imageTextInfoLeft", new JSONObject()
                        .put("type", 1)
                        .put("picInfo", new JSONObject().put("type", 1).put("pic", PIC_SMALL))
                        .put("textInfo", new JSONObject()
                                .put("title", app)
                                .put("content", "")
                                .put("showHighlightColor", false)
                                .put("narrowFont", false)))
                .put("textInfo", textInfo);

        JSONObject island = new JSONObject()
                .put("islandProperty", 1)
                .put("islandTimeout", 24 * 60 * 60)
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
                .put("hintInfo", hintInfo)
                .put("param_island", island);

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
            apply(context, notification,
                    context.getString(R.string.island_test_title), "00:10",
                    System.currentTimeMillis(), false);
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
