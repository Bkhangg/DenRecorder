package com.bkggds.denrecorder;

import android.content.Context;
import android.content.pm.PackageManager;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/**
 * The small part of Shizuku this app needs: it runs a shell command as the
 * phone's shell or root user, which is the only way to hand a normal install
 * the Xiaomi focus-notification permission the Super Island needs.
 */
public final class ShizukuBridge {

    private static final String TAG = "ShizukuBridge";

    /** The Shizuku manager app. */
    public static final String PACKAGE = "moe.shizuku.privileged.api";

    /** Signature permission that lets SystemUI draw this app in the island. */
    public static final String FOCUS_PERMISSION = "miui.permission.USE_FOCUS_NOTIFICATION";

    private ShizukuBridge() { }

    public static boolean installed(Context context) {
        try {
            context.getPackageManager().getPackageInfo(PACKAGE, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Shizuku is installed and its service is running right now. */
    public static boolean running() {
        try {
            return rikka.shizuku.Shizuku.pingBinder();
        } catch (Throwable e) {
            return false;
        }
    }

    /** The user allowed this app in Shizuku. */
    public static boolean allowed() {
        try {
            return rikka.shizuku.Shizuku.checkSelfPermission()
                    == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable e) {
            return false;
        }
    }

    public static void requestPermission(int requestCode) {
        try {
            rikka.shizuku.Shizuku.requestPermission(requestCode);
        } catch (Throwable e) {
            Log.w(TAG, "requestPermission failed", e);
        }
    }

    /** Shizuku runs as root, so the island permission can really be granted. */
    public static boolean isRoot() {
        try {
            return rikka.shizuku.Shizuku.getUid() == 0;
        } catch (Throwable e) {
            return false;
        }
    }

    /** Whether the phone already granted the Xiaomi island permission to us. */
    public static boolean hasIslandPermission(Context context) {
        try {
            return context.checkSelfPermission(FOCUS_PERMISSION)
                    == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable e) {
            return false;
        }
    }

    /**
     * Runs {@code pm grant} through Shizuku and returns what the shell said, so
     * the screen can show the real reason when it does not work.
     */
    public static String grantIslandPermission(Context context) {
        String out = run("pm", "grant", context.getPackageName(), FOCUS_PERMISSION);
        Log.i(TAG, "pm grant: " + out);
        return out;
    }

    /** Runs a command through Shizuku and returns the combined output. */
    public static String run(String... command) {
        if (!running() || !allowed()) return "Shizuku is not ready";
        Process process = null;
        try {
            java.lang.reflect.Method method = rikka.shizuku.Shizuku.class.getDeclaredMethod(
                    "newProcess", String[].class, String[].class, String.class);
            method.setAccessible(true);
            process = (Process) method.invoke(null, command, null, null);
            if (process == null) return "Shizuku could not start the command";
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            InputStream in = process.getInputStream();
            byte[] chunk = new byte[4096];
            int read;
            while ((read = in.read(chunk)) > 0) buffer.write(chunk, 0, read);
            InputStream err = process.getErrorStream();
            while ((read = err.read(chunk)) > 0) buffer.write(chunk, 0, read);
            process.waitFor();
            String text = buffer.toString("UTF-8").trim();
            return text.isEmpty() ? "OK" : text;
        } catch (Throwable e) {
            Log.w(TAG, "run failed", e);
            return String.valueOf(e.getMessage() == null ? e : e.getMessage());
        } finally {
            if (process != null) {
                try {
                    process.destroy();
                } catch (Exception ignored) { }
            }
        }
    }
}
