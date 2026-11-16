package com.bkggds.denrecorder;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.provider.Settings;

/**
 * Keeps the screen in one orientation while a recording runs, so the video
 * does not turn halfway through when the phone is moved. The user's own
 * rotation settings are put back as soon as the recording ends.
 */
final class RotationLock {

    private static final String PREFS = "rotation_lock";
    private static final String KEY_ACTIVE = "active";
    private static final String KEY_AUTO = "auto";
    private static final String KEY_USER = "user";

    private RotationLock() { }

    /** Writing system settings needs a special grant from Android 6 on. */
    static boolean canWrite(Context context) {
        if (Build.VERSION.SDK_INT < 23) return true;
        try {
            return Settings.System.canWrite(context);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Locks the screen rotation for the recording: "portrait", "landscape" or
     * "auto" (no lock). Returns true when a lock is now in place.
     */
    static boolean apply(Context context, String mode) {
        if (mode == null || mode.isEmpty() || "auto".equals(mode)) return false;
        if (!canWrite(context)) return false;
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            if (!prefs.getBoolean(KEY_ACTIVE, false)) {
                prefs.edit()
                        .putBoolean(KEY_ACTIVE, true)
                        .putInt(KEY_AUTO, Settings.System.getInt(
                                context.getContentResolver(),
                                Settings.System.ACCELEROMETER_ROTATION, 1))
                        .putInt(KEY_USER, Settings.System.getInt(
                                context.getContentResolver(),
                                Settings.System.USER_ROTATION, 0))
                        .apply();
            }
            Settings.System.putInt(context.getContentResolver(),
                    Settings.System.ACCELEROMETER_ROTATION, 0);
            Settings.System.putInt(context.getContentResolver(),
                    Settings.System.USER_ROTATION, "landscape".equals(mode) ? 1 : 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Puts the rotation settings back the way the user had them. */
    static void release(Context context) {
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            if (!prefs.getBoolean(KEY_ACTIVE, false)) return;
            int auto = prefs.getInt(KEY_AUTO, 1);
            int user = prefs.getInt(KEY_USER, 0);
            prefs.edit().putBoolean(KEY_ACTIVE, false).apply();
            if (!canWrite(context)) return;
            Settings.System.putInt(context.getContentResolver(),
                    Settings.System.ACCELEROMETER_ROTATION, auto);
            Settings.System.putInt(context.getContentResolver(),
                    Settings.System.USER_ROTATION, user);
        } catch (Exception ignored) { }
    }
}
