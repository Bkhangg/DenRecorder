package com.bkggds.denrecorder;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.util.Locale;

/**
 * Skips Android's "start recording?" dialog: while the system asks, this taps
 * its own "share screen" button for the user over Shizuku, so one tap on the
 * start button (or the Quick Settings tile) is enough. Android needs the
 * consent every time, so the dialog still flashes by; the app never records
 * anything the system did not just show on the screen.
 */
public final class FastStart {

    private static final String TAG = "FastStart";
    private static final String KEY = "fastStart";
    private static final String DUMP = "/data/local/tmp/denrecorder_ui.xml";

    /** Labels of the button that lets the capture start. */
    private static final String[] YES = {
            "start now", "start recording", "start capture", "share screen",
            "share your screen", "start", "allow", "continue", "ok",
            "chia sẻ màn hình", "chia sẻ toàn bộ màn hình", "chia sẻ", "bắt đầu ngay",
            "bắt đầu", "cho phép", "tiếp tục", "đồng ý",
            "开始", "开始录制", "立即开始", "开始共享", "共享屏幕", "允许", "确定"
    };

    /** Labels of the button that stops it; never tapped. */
    private static final String[] NO = {
            "cancel", "deny", "stop", "hủy", "huỷ", "dừng", "từ chối", "取消", "停止", "拒绝"
    };

    private FastStart() { }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    public static boolean enabled(Context context) {
        try {
            return prefs(context).getBoolean(KEY, false);
        } catch (Exception e) {
            return false;
        }
    }

    public static void setEnabled(Context context, boolean on) {
        prefs(context).edit().putBoolean(KEY, on).apply();
    }

    /** Shizuku can run the two shell commands this needs. */
    public static boolean available() {
        try {
            return ShizukuBridge.running() && ShizukuBridge.allowed();
        } catch (Throwable e) {
            return false;
        }
    }

    /**
     * Called right after the capture dialog was asked for. On a background
     * thread it dumps the dialog and taps its positive button once.
     */
    public static void arm(Context context) {
        if (!enabled(context)) return;
        final boolean ok = available();
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                if (!ok) return;
                for (int attempt = 0; attempt < 6; attempt++) {
                    sleep(attempt == 0 ? 300 : 450);
                    String xml = ShizukuBridge.run("sh", "-c",
                            "uiautomator dump " + DUMP + " >/dev/null 2>&1; cat " + DUMP + " 2>/dev/null");
                    int[] point = confirmPoint(xml);
                    if (point == null) continue;
                    String out = ShizukuBridge.run("input", "tap",
                            String.valueOf(point[0]), String.valueOf(point[1]));
                    Log.i(TAG, "tapped " + point[0] + "," + point[1] + " -> " + out);
                    return;
                }
                Log.i(TAG, "the capture dialog was not found");
            }
        }, "fast-start");
        thread.setDaemon(true);
        thread.start();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) { }
    }

    /** The middle of the button that starts the capture, or null. */
    private static int[] confirmPoint(String xml) {
        if (xml == null || !xml.contains("com.android.systemui")) return null;
        int[] best = null;
        int bestRank = Integer.MIN_VALUE;
        int index = 0;
        while (true) {
            int start = xml.indexOf("<node ", index);
            if (start < 0) break;
            int end = xml.indexOf('>', start);
            if (end < 0) break;
            String node = xml.substring(start, end);
            index = end + 1;
            if (!"true".equals(attr(node, "clickable"))) continue;
            String id = attr(node, "resource-id");
            String label = (attr(node, "text") + " " + attr(node, "content-desc"))
                    .trim().toLowerCase(Locale.ROOT);
            int[] rect = bounds(node);
            if (rect == null) continue;
            boolean named = false;
            for (String word : YES) if (label.contains(word)) named = true;
            boolean denied = false;
            for (String word : NO) if (label.contains(word)) denied = true;
            if (denied) continue;
            if (id != null && (id.endsWith(":id/button1") || id.contains("start_now")
                    || id.contains("start_button") || id.contains("permission_allow"))) {
                named = true;
            }
            if (!named && label.isEmpty()) continue;
            int cx = (rect[0] + rect[2]) / 2;
            int cy = (rect[1] + rect[3]) / 2;
            // A named button wins; otherwise the lowest, most right-hand button
            // of the screen is the one that starts the capture.
            int rank = (named ? 1000000 : 0) + cy * 4 + Math.min(cx, 3000);
            if (rank > bestRank) {
                bestRank = rank;
                best = new int[]{cx, cy};
            }
        }
        return best;
    }

    private static String attr(String node, String name) {
        String key = name + "=\"";
        int start = node.indexOf(key);
        if (start < 0) return "";
        start += key.length();
        int end = node.indexOf('"', start);
        if (end < 0) return "";
        return node.substring(start, end);
    }

    /** [left, top, right, bottom] from bounds="[l,t][r,b]", or null. */
    private static int[] bounds(String node) {
        try {
            String value = attr(node, "bounds");
            String[] parts = value.replace("][", ",").replace("[", "").replace("]", "").split(",");
            if (parts.length != 4) return null;
            int l = Integer.parseInt(parts[0].trim());
            int t = Integer.parseInt(parts[1].trim());
            int r = Integer.parseInt(parts[2].trim());
            int b = Integer.parseInt(parts[3].trim());
            if (r <= l || b <= t) return null;
            return new int[]{l, t, r, b};
        } catch (Exception e) {
            return null;
        }
    }
}
