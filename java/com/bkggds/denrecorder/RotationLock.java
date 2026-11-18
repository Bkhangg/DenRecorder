package com.bkggds.denrecorder;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;

/**
 * Keeps the screen in one orientation while a recording runs, so the video
 * does not turn halfway through when the phone is moved. The user's own
 * rotation settings (auto-rotate on, or the lock they had) are put back as
 * soon as the recording ends.
 *
 * <p>The settings are only written back when they were really changed, and the
 * "locked by us" flag is cleared only once the write went through, so a crash
 * or a force-stop in the middle of a recording does not leave the phone stuck
 * in the recorded orientation: the next time the app is opened it puts the
 * user's rotation back (see {@link #restoreIfStale(Context)}).
 */
final class RotationLock {

    private static final String TAG = "RotationLock";
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
            int wanted = "landscape".equals(mode) ? 1 : 0;
            int nowAuto = Settings.System.getInt(context.getContentResolver(),
                    Settings.System.ACCELEROMETER_ROTATION, 1);
            int nowUser = Settings.System.getInt(context.getContentResolver(),
                    Settings.System.USER_ROTATION, 0);
            // Already held this way by the user's own rotation lock: nothing to
            // change, so nothing can be left behind afterwards.
            if (nowAuto == 0 && nowUser == wanted) {
                Log.i(TAG, "already locked to " + mode);
                return false;
            }
            SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            if (!prefs.getBoolean(KEY_ACTIVE, false)) {
                int auto = Settings.System.getInt(context.getContentResolver(),
                        Settings.System.ACCELEROMETER_ROTATION, 1);
                int user = Settings.System.getInt(context.getContentResolver(),
                        Settings.System.USER_ROTATION, 0);
                prefs.edit()
                        .putBoolean(KEY_ACTIVE, true)
                        .putInt(KEY_AUTO, auto)
                        .putInt(KEY_USER, user)
                        .apply();
                Log.i(TAG, "saved rotation auto=" + auto + " user=" + user);
            }
            write(context, Settings.System.ACCELEROMETER_ROTATION, 0);
            boolean ok = write(context, Settings.System.USER_ROTATION, wanted);
            Log.i(TAG, "locked to " + mode + " -> " + ok);
            if (!ok) {
                // Half a lock is worse than none: put the old state back.
                release(context);
                return false;
            }
            return true;
        } catch (Exception e) {
            Log.w(TAG, "apply failed", e);
            return false;
        }
    }

    /** Puts the rotation settings back the way the user had them. */
    static void release(Context context) {
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            if (!prefs.getBoolean(KEY_ACTIVE, false)) return;
            if (!canWrite(context)) {
                // No grant right now: keep the values and try again next time
                // the app is opened.
                Log.w(TAG, "release skipped: no write-settings grant");
                return;
            }
            int auto = prefs.getInt(KEY_AUTO, 1);
            int user = prefs.getInt(KEY_USER, 0);
            // Both writes must land: if only one does, the phone can be left
            // locked sideways, which is worse than staying flagged as ours.
            write(context, Settings.System.ACCELEROMETER_ROTATION, auto);
            write(context, Settings.System.USER_ROTATION, user);
            boolean done = Settings.System.getInt(context.getContentResolver(),
                    Settings.System.ACCELEROMETER_ROTATION, -1) == auto
                    && Settings.System.getInt(context.getContentResolver(),
                    Settings.System.USER_ROTATION, -1) == user;
            if (done) {
                prefs.edit().putBoolean(KEY_ACTIVE, false).apply();
            }
            Log.i(TAG, "restored rotation auto=" + auto + " user=" + user + " -> " + done);
        } catch (Exception e) {
            Log.w(TAG, "release failed", e);
        }
    }

    /**
     * A crash, an update or a force-stop can skip {@link #release}. Called when
     * the app is opened: it puts the user's rotation back, unless a recording
     * is running right now (that one still needs the lock).
     */
    static void restoreIfStale(Context context) {
        try {
            if (RecorderService.isRecording) return;
            release(context);
        } catch (Exception e) {
            Log.w(TAG, "stale restore failed", e);
        }
    }

    /** Writes a system setting and reports whether it really changed. */
    private static boolean write(Context context, String key, int value) {
        try {
            Settings.System.putInt(context.getContentResolver(), key, value);
            if (Settings.System.getInt(context.getContentResolver(), key, -1) == value) {
                return true;
            }
            // A provider can lag behind by a moment.
            Thread.sleep(60);
            return Settings.System.getInt(context.getContentResolver(), key, -1) == value;
        } catch (Exception e) {
            Log.w(TAG, "write " + key + " failed", e);
            return false;
        }
    }
}
