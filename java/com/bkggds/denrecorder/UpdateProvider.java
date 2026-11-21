package com.bkggds.denrecorder;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * Hands the downloaded APK to Android's installer. Since Android 7 an app
 * cannot pass a file path around any more, so the file is offered as a
 * content:// address the installer is allowed to read once.
 */
public class UpdateProvider extends ContentProvider {

    static final String AUTHORITY = "com.bkggds.denrecorder.updates";

    /** The APK the next install intent points at. */
    private static File pending;

    static void offer(File apk) {
        pending = apk;
    }

    /** The content:// address of a file in the cache folder of updates. */
    static Uri uriFor(File apk) {
        return new Uri.Builder()
                .scheme("content")
                .authority(AUTHORITY)
                .appendPath(apk.getName())
                .build();
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public String getType(Uri uri) {
        return "application/vnd.android.package-archive";
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File file = pending;
        if (file == null || !file.exists()) {
            // The process was restarted between the intent and the read: the
            // name in the address still points at the one download folder.
            String name = uri.getLastPathSegment();
            File folder = new File(getContext().getCacheDir(), "updates");
            file = name == null ? null : new File(folder, name);
        }
        if (file == null || !file.exists() || !isInUpdates(file)) {
            throw new FileNotFoundException(uri.toString());
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    /** Only a file of the update folder may be served, never anything else. */
    private boolean isInUpdates(File file) {
        try {
            File folder = new File(getContext().getCacheDir(), "updates").getCanonicalFile();
            return file.getCanonicalFile().getParentFile().equals(folder);
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
