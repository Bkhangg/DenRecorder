package com.bkggds.denrecorder;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;
import android.view.Surface;

/**
 * Keeps the screen in one orientation while a recording runs, so the video
 * does not turn halfway through when the phone is moved, and puts the phone's
 * own rotation back as soon as the recording ends.
 *
 * <p>The settings are changed only through Android's public WRITE_SETTINGS
 * grant. If that grant is unavailable, recording follows the current display.
 *
 * <p>The values are only written back when they were really changed, and the
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
    /** The phone's own rotation setting, as last seen with the app in front. */
    private static final String KEY_LOCK_AUTO = "userLockAuto";
    private static final String KEY_LOCK_USER = "userLockUser";

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

    /** True when Android has granted the app permission to modify system settings. */
    static boolean usable(Context context) {
        return canWrite(context);
    }

    /**
     * Locks the screen rotation for the recording: "portrait", "landscape" or
     * "auto" (no lock). Returns true when a lock is now in place.
     */
    static boolean apply(Context context, String mode) {
        if (mode == null || mode.isEmpty() || "auto".equals(mode)) return false;
        if (!usable(context)) return false;
        try {
            int wanted = "landscape".equals(mode) ? 1 : 0;
            int nowAuto = read(context, Settings.System.ACCELEROMETER_ROTATION, 1);
            int nowUser = read(context, Settings.System.USER_ROTATION, 0);
            // Already held this way by the user's own rotation lock: nothing to
            // change, so nothing can be left behind afterwards.
            if (nowAuto == 0 && nowUser == wanted) {
                Log.i(TAG, "already locked to " + mode);
                return false;
            }
            SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            if (!prefs.getBoolean(KEY_ACTIVE, false)) {
                prefs.edit()
                        .putBoolean(KEY_ACTIVE, true)
                        .putInt(KEY_AUTO, nowAuto)
                        .putInt(KEY_USER, nowUser)
                        .apply();
                Log.i(TAG, "saved rotation auto=" + nowAuto + " user=" + nowUser);
            }
            set(context, Settings.System.ACCELEROMETER_ROTATION, 0);
            boolean ok = set(context, Settings.System.USER_ROTATION, wanted);
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

    /**
     * Remembers the phone's own rotation setting while the app is in front and
     * nothing is being recorded. A landscape game can turn that setting around
     * behind our back; this is the value it is put back to afterwards.
     */
    static void rememberUserLock(Context context) {
        try {
            if (RecorderService.isRecording) return;
            int auto = read(context, Settings.System.ACCELEROMETER_ROTATION, 1);
            int user = read(context, Settings.System.USER_ROTATION, 0);
            SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            if (prefs.getInt(KEY_LOCK_AUTO, -1) == auto
                    && prefs.getInt(KEY_LOCK_USER, -1) == user) {
                return;
            }
            prefs.edit().putInt(KEY_LOCK_AUTO, auto).putInt(KEY_LOCK_USER, user).apply();
            Log.i(TAG, "phone rotation kept auto=" + auto + " user=" + user);
        } catch (Exception e) {
            Log.w(TAG, "remember failed", e);
        }
    }

    /** Puts the rotation settings back the way the user had them. */
    static void release(Context context) {
        restoreSnapshot(context);
        restoreUserLock(context);
    }

    /** Puts back the values saved when our own lock went in. */
    private static void restoreSnapshot(Context context) {
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            if (!prefs.getBoolean(KEY_ACTIVE, false)) return;
            if (!usable(context)) {
                // No grant right now: keep the values and try again next time
                // the app is opened.
                Log.w(TAG, "release skipped: rotation cannot be changed");
                return;
            }
            int auto = prefs.getInt(KEY_AUTO, 1);
            int user = prefs.getInt(KEY_USER, 0);
            // Both writes must land: if only one does, the phone can be left
            // locked sideways, which is worse than staying flagged as ours.
            set(context, Settings.System.ACCELEROMETER_ROTATION, auto);
            set(context, Settings.System.USER_ROTATION, user);
            boolean done = read(context, Settings.System.ACCELEROMETER_ROTATION, -1) == auto
                    && read(context, Settings.System.USER_ROTATION, -1) == user;
            if (done) {
                prefs.edit().putBoolean(KEY_ACTIVE, false).apply();
            }
            Log.i(TAG, "restored rotation auto=" + auto + " user=" + user + " -> " + done);
        } catch (Exception e) {
            Log.w(TAG, "release failed", e);
        }
    }

    /**
     * A game that asks for landscape can leave the phone turned around even
     * with the user's rotation lock on: the system keeps the game's rotation in
     * the lock, and every app that does not force its own way round (the
     * gallery, a player) then opens sideways. This puts the lock the phone had
     * before back.
     */
    private static void restoreUserLock(Context context) {
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            int lockAuto = prefs.getInt(KEY_LOCK_AUTO, -1);
            int lockUser = prefs.getInt(KEY_LOCK_USER, -1);
            if (lockAuto != 0 || lockUser < 0) return;
            if (!usable(context)) return;
            int auto = read(context, Settings.System.ACCELEROMETER_ROTATION, 1);
            int user = read(context, Settings.System.USER_ROTATION, 0);
            // The user changed the lock themselves in the meantime: leave it.
            if (auto != 0) return;
            if (user != lockUser) {
                boolean ok = set(context, Settings.System.USER_ROTATION, lockUser);
                Log.i(TAG, "phone lock back to " + lockUser + " -> " + ok);
                return;
            }
            // The setting reads right but the screen is turned the other way:
            // the system kept the game's rotation in memory. Turning it to the
            // other value and back makes it follow the setting again.
            boolean sideways = isSideways(displayRotation(context));
            boolean wantSideways = lockUser == 1 || lockUser == 3;
            if (sideways == wantSideways) return;
            set(context, Settings.System.USER_ROTATION, wantSideways ? 0 : 1);
            boolean ok = set(context, Settings.System.USER_ROTATION, lockUser);
            Log.i(TAG, "phone lock nudged back to " + lockUser + " -> " + ok);
        } catch (Exception e) {
            Log.w(TAG, "user lock restore failed", e);
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

    private static int displayRotation(Context context) {
        try {
            android.hardware.display.DisplayManager manager =
                    (android.hardware.display.DisplayManager)
                            context.getSystemService(Context.DISPLAY_SERVICE);
            android.view.Display display = manager == null ? null
                    : manager.getDisplay(android.view.Display.DEFAULT_DISPLAY);
            return display == null ? Surface.ROTATION_0 : display.getRotation();
        } catch (Exception e) {
            return Surface.ROTATION_0;
        }
    }

    private static boolean isSideways(int rotation) {
        return rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270;
    }

    private static int read(Context context, String key, int fallback) {
        try {
            return Settings.System.getInt(context.getContentResolver(), key, fallback);
        } catch (Exception e) {
            return fallback;
        }
    }

    /**
     * Writes a system setting with Android's public Settings API.
     */
    private static boolean set(Context context, String key, int value) {
        try {
            if (!canWrite(context)) return false;
            boolean wrote = Settings.System.putInt(context.getContentResolver(), key, value);
            return wrote && read(context, key, -1) == value;
        } catch (Exception e) {
            Log.w(TAG, "write " + key + " failed", e);
            return false;
        }
    }
}
