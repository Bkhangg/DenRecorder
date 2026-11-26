package com.bkggds.denrecorder;

import android.content.Context;
import android.content.pm.PackageManager;


/**
 * Passive status checks for optional Shizuku integration. DenRecorder does not
 * use Shizuku to execute commands or grant permissions.
 */
public final class ShizukuBridge {

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

    /** Whether the phone already granted the Xiaomi island permission to us. */
    public static boolean hasIslandPermission(Context context) {
        try {
            return context.checkSelfPermission(FOCUS_PERMISSION)
                    == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable e) {
            return false;
        }
    }

}
