package com.bkggds.denrecorder;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;

/**
 * The newest release of this app on GitHub, and the two steps that install
 * it: the APK is fetched into the cache and handed to Android's installer, so
 * an update happens here instead of in a browser.
 */
final class UpdateChecker {

    private static final String TAG = "UpdateChecker";
    private static final String RELEASE_API =
            "https://api.github.com/repos/Bkhangg/DenRecorder/releases/latest";
    /** Shown to GitHub, which refuses a request without one. */
    private static final String USER_AGENT = "DenRecorder";

    private UpdateChecker() { }

    /** One release: the version without its "v", its notes and its APK. */
    static final class Release {
        final String version;
        final String notes;
        final String apkUrl;
        final long size;

        Release(String version, String notes, String apkUrl, long size) {
            this.version = version;
            this.notes = notes == null ? "" : notes;
            this.apkUrl = apkUrl;
            this.size = size;
        }
    }

    interface OnCheck {
        /** One of the two is null: the release, or why it could not be read. */
        void done(Release release, String error);
    }

    interface OnDownload {
        void progress(int percent);

        /** One of the two is null: the saved file, or why it failed. */
        void done(File apk, String error);
    }

    /** True when the release is ahead of what is installed ("1.10.12" > "1.10.9"). */
    static boolean isNewer(String release, String installed) {
        return compare(release, installed) > 0;
    }

    private static int compare(String a, String b) {
        String[] left = a == null ? new String[0] : a.split("[^0-9]+");
        String[] right = b == null ? new String[0] : b.split("[^0-9]+");
        int count = Math.max(left.length, right.length);
        for (int i = 0; i < count; i++) {
            int x = number(left, i);
            int y = number(right, i);
            if (x != y) return x > y ? 1 : -1;
        }
        return 0;
    }

    private static int number(String[] parts, int index) {
        if (index >= parts.length || parts[index].isEmpty()) return 0;
        try {
            return Integer.parseInt(parts[index]);
        } catch (Exception e) {
            return 0;
        }
    }

    /** Reads the latest release off the UI thread; the callback lands on the main one. */
    static void check(final OnCheck callback) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                Release release = null;
                String error = null;
                try {
                    release = readLatest();
                } catch (Exception e) {
                    error = describe(e);
                    Log.w(TAG, "Could not read the latest release", e);
                }
                final Release found = release;
                final String failed = error;
                new Handler(Looper.getMainLooper()).post(new Runnable() {
                    @Override
                    public void run() {
                        callback.done(found, failed);
                    }
                });
            }
        }, "update-check").start();
    }

    private static Release readLatest() throws Exception {
        HttpURLConnection connection =
                (HttpURLConnection) new URL(RELEASE_API).openConnection();
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(15000);
        connection.setRequestProperty("Accept", "application/vnd.github+json");
        connection.setRequestProperty("User-Agent", USER_AGENT);
        try {
            int code = connection.getResponseCode();
            if (code != 200) throw new Exception("HTTP " + code);
            JSONObject json = new JSONObject(read(connection.getInputStream()));
            String version = json.optString("tag_name", "")
                    .replaceFirst("^[vV]", "").trim();
            String notes = json.optString("body", "");
            String apkUrl = null;
            long size = 0;
            JSONArray assets = json.optJSONArray("assets");
            for (int i = 0; assets != null && i < assets.length(); i++) {
                JSONObject asset = assets.getJSONObject(i);
                String name = asset.optString("name", "");
                if (!name.toLowerCase(Locale.US).endsWith(".apk")) continue;
                apkUrl = asset.optString("browser_download_url", null);
                size = asset.optLong("size", 0);
                // A release carries one APK; prefer the one of this version.
                if (version.isEmpty() || name.contains(version)) break;
            }
            if (version.isEmpty() || apkUrl == null) {
                throw new Exception("No APK in the release");
            }
            return new Release(version, notes, apkUrl, size);
        } finally {
            connection.disconnect();
        }
    }

    private static String read(InputStream in) throws Exception {
        StringBuilder out = new StringBuilder();
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, "UTF-8"));
        try {
            String line;
            while ((line = reader.readLine()) != null) out.append(line).append('\n');
        } finally {
            try { reader.close(); } catch (Exception ignored) { }
        }
        return out.toString();
    }

    private static String describe(Exception e) {
        String message = e.getMessage();
        return message == null || message.isEmpty() ? e.toString() : message;
    }

    /** Where a release's APK is kept once it is here. */
    static File apkFile(Context context, Release release) {
        File folder = new File(context.getCacheDir(), "updates");
        if (!folder.exists()) folder.mkdirs();
        return new File(folder, "DenRecorder-" + release.version + ".apk");
    }

    /** Fetches the APK, or keeps the one already fetched; progress is on the main thread. */
    static void download(final Context context, final Release release,
                         final OnDownload callback) {
        final Handler main = new Handler(Looper.getMainLooper());
        new Thread(new Runnable() {
            @Override
            public void run() {
                final File target = apkFile(context, release);
                Log.i(TAG, "Download of " + release.version + " asked for");
                if (target.exists() && release.size > 0 && target.length() == release.size) {
                    Log.i(TAG, "Already downloaded: " + target.length() + " bytes");
                    main.post(new Runnable() {
                        @Override
                        public void run() { callback.done(target, null); }
                    });
                    return;
                }
                final File part = new File(target.getPath() + ".part");
                try {
                    HttpURLConnection connection =
                            (HttpURLConnection) new URL(release.apkUrl).openConnection();
                    connection.setConnectTimeout(10000);
                    connection.setReadTimeout(20000);
                    connection.setRequestProperty("User-Agent", USER_AGENT);
                    try {
                        int code = connection.getResponseCode();
                        if (code != 200) throw new Exception("HTTP " + code);
                        Log.i(TAG, "Download started: HTTP " + code);
                        long total = release.size > 0
                                ? release.size : connection.getContentLength();
                        long done = 0;
                        int lastPercent = -1;
                        InputStream in = connection.getInputStream();
                        OutputStream out = new FileOutputStream(part);
                        try {
                            byte[] buffer = new byte[64 * 1024];
                            int count;
                            while ((count = in.read(buffer)) > 0) {
                                out.write(buffer, 0, count);
                                done += count;
                                if (total > 0) {
                                    int percent = (int) (done * 100 / total);
                                    if (percent != lastPercent) {
                                        lastPercent = percent;
                                        final int value = percent;
                                        main.post(new Runnable() {
                                            @Override
                                            public void run() { callback.progress(value); }
                                        });
                                    }
                                }
                            }
                        } finally {
                            try { out.close(); } catch (Exception ignored) { }
                            try { in.close(); } catch (Exception ignored) { }
                        }
                    } finally {
                        connection.disconnect();
                    }
                    if (release.size > 0 && part.length() != release.size) {
                        throw new Exception("The download is incomplete");
                    }
                    if (target.exists() && !target.delete()) {
                        throw new Exception("Could not replace the old file");
                    }
                    if (!part.renameTo(target)) throw new Exception("Could not save the APK");
                    Log.i(TAG, "Downloaded " + target.length() + " bytes");
                    main.post(new Runnable() {
                        @Override
                        public void run() { callback.done(target, null); }
                    });
                } catch (Exception e) {
                    part.delete();
                    final String error = describe(e);
                    Log.w(TAG, "Could not download the update", e);
                    main.post(new Runnable() {
                        @Override
                        public void run() { callback.done(null, error); }
                    });
                }
            }
        }, "update-download").start();
    }

    /**
     * Hands the APK to Android's own installer. The phone still asks before it
     * replaces the app; nothing here can skip that.
     */
    static boolean install(Context context, File apk) {
        // A content:// address is what the installer reads: a plain file path
        // is refused since Android 7.
        try {
            UpdateProvider.offer(apk);
            Intent view = new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(UpdateProvider.uriFor(apk),
                            "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                            | Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(view);
            Log.i(TAG, "Installer opened for " + apk.getName());
            return true;
        } catch (Exception e) {
            Log.w(TAG, "The installer did not open", e);
        }
        // Nothing handles the intent: hand the APK over as an install session,
        // which Android shows with the same confirmation.
        return installSession(context, apk);
    }

    private static boolean installSession(Context context, File apk) {
        try {
            PackageInstaller installer =
                    context.getPackageManager().getPackageInstaller();
            PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                    PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            params.setAppPackageName(context.getPackageName());
            int id = installer.createSession(params);
            PackageInstaller.Session session = installer.openSession(id);
            FileInputStream in = new FileInputStream(apk);
            OutputStream out = session.openWrite("DenRecorder", 0, apk.length());
            try {
                byte[] buffer = new byte[64 * 1024];
                int count;
                while ((count = in.read(buffer)) > 0) out.write(buffer, 0, count);
                session.fsync(out);
            } finally {
                try { out.close(); } catch (Exception ignored) { }
                try { in.close(); } catch (Exception ignored) { }
            }
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
            PendingIntent result = PendingIntent.getBroadcast(context, id,
                    new Intent(context.getPackageName() + ".INSTALL_RESULT")
                            .setPackage(context.getPackageName()), flags);
            session.commit(result.getIntentSender());
            Log.i(TAG, "Install session " + id + " committed");
            return true;
        } catch (Exception e) {
            Log.w(TAG, "The installer refused the APK", e);
            return false;
        }
    }
}
