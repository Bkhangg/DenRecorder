package com.bkggds.denrecorder;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.util.Log;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.Locale;

/**
 * Cuts a piece out of a recording without re-encoding it: the same video and
 * audio samples are copied into a new file, starting at the key frame before
 * the chosen start. The whole thing runs off the UI thread.
 */
final class VideoTrimmer {

    private static final String TAG = "VideoTrimmer";

    private VideoTrimmer() { }

    interface OnTrim {
        /** One of the two is null: the new file's name, or why it failed. */
        void done(String fileName, String error);
    }

    static void start(final Context context, final Uri source, final String sourceName,
                      final String folder, final long startMs, final long endMs,
                      final OnTrim callback) {
        final Handler main = new Handler(Looper.getMainLooper());
        new Thread(new Runnable() {
            @Override
            public void run() {
                String name = null;
                String error = null;
                try {
                    name = trim(context, source, sourceName, folder, startMs, endMs);
                } catch (Exception e) {
                    error = describe(e);
                    Log.w(TAG, "Could not trim the recording", e);
                }
                final String saved = name;
                final String failed = error;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        callback.done(saved, failed);
                    }
                });
            }
        }, "video-trim").start();
    }

    /** "DenRecorder_20261120_124538.mp4" becomes "… (trim).mp4". */
    static String trimmedName(String sourceName) {
        String base = sourceName == null ? "DenRecorder" : sourceName;
        if (base.toLowerCase(Locale.US).endsWith(".mp4")) {
            base = base.substring(0, base.length() - 4);
        }
        if (!base.startsWith("DenRecorder_")) base = "DenRecorder_" + base;
        return base + " (trim).mp4";
    }

    private static String trim(Context context, Uri source, String sourceName, String folder,
                               long startMs, long endMs) throws Exception {
        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(context, source, null);
        } catch (Exception e) {
            extractor.release();
            throw e;
        }
        MediaMuxer muxer = null;
        ParcelFileDescriptor descriptor = null;
        Uri outputUri = null;
        File legacyFile = null;
        boolean muxing = false;
        try {
            int videoTrack = -1;
            int audioTrack = -1;
            MediaFormat videoFormat = null;
            MediaFormat audioFormat = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime == null) continue;
                if (videoTrack < 0 && mime.startsWith("video/")) {
                    videoTrack = i;
                    videoFormat = format;
                } else if (audioTrack < 0 && mime.startsWith("audio/")) {
                    audioTrack = i;
                    audioFormat = format;
                }
            }
            if (videoTrack < 0 || videoFormat == null) throw new Exception("No video track");

            extractor.selectTrack(videoTrack);
            if (audioTrack >= 0) extractor.selectTrack(audioTrack);

            // A cut can only begin on a key frame: the sync frame at or before
            // the chosen second is where the video actually starts.
            long wantedUs = Math.max(0, startMs) * 1000L;
            extractor.seekTo(wantedUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
            long baseUs = extractor.getSampleTime();
            if (baseUs < 0) baseUs = wantedUs;
            long endUs = endMs * 1000L;
            if (endUs <= baseUs + 100000L) throw new Exception("The cut is empty");

            String name = trimmedName(sourceName);
            String where = folder == null || folder.isEmpty()
                    ? ScreenRecorder.DEFAULT_FOLDER : folder;
            boolean scoped = Build.VERSION.SDK_INT >= 29;
            if (scoped) {
                outputUri = insertPending(context, name, where);
                if (outputUri == null) throw new Exception("Could not create the file");
                descriptor = context.getContentResolver().openFileDescriptor(outputUri, "rw");
                if (descriptor == null) throw new Exception("Could not open the file");
                muxer = new MediaMuxer(descriptor.getFileDescriptor(),
                        MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            } else {
                File dir = ScreenRecorder.publicFolder(where);
                if (dir == null) throw new Exception("Could not find the folder");
                if (!dir.exists() && !dir.mkdirs() && !dir.exists()) {
                    throw new Exception("Could not create the folder");
                }
                legacyFile = new File(dir, name);
                muxer = new MediaMuxer(legacyFile.getAbsolutePath(),
                        MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            }

            int videoSamples = 0;
            int audioSamples = 0;
            int muxVideo = muxer.addTrack(videoFormat);
            int muxAudio = audioTrack >= 0 && audioFormat != null
                    ? muxer.addTrack(audioFormat) : -1;
            muxer.start();
            muxing = true;

            ByteBuffer buffer = ByteBuffer.allocateDirect(1024 * 1024);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            while (true) {
                int track = extractor.getSampleTrackIndex();
                if (track < 0) break;
                long time = extractor.getSampleTime();
                if (time < 0 || time > endUs) break;
                // A key frame of a high bitrate recording can be several
                // megabytes, and readSampleData refuses a buffer that small.
                int size;
                while (true) {
                    try {
                        size = extractor.readSampleData(buffer, 0);
                        break;
                    } catch (IllegalArgumentException e) {
                        if (buffer.capacity() >= 32 * 1024 * 1024) throw e;
                        buffer = ByteBuffer.allocateDirect(buffer.capacity() * 2);
                    }
                }
                if (size < 0) break;
                if (time >= baseUs) {
                    int muxTrack = track == videoTrack ? muxVideo
                            : (track == audioTrack ? muxAudio : -1);
                    if (muxTrack >= 0) {
                        info.offset = 0;
                        info.size = size;
                        info.presentationTimeUs = time - baseUs;
                        // The first video sample of a cut has to stay a key frame.
                        info.flags = (extractor.getSampleFlags()
                                & MediaExtractor.SAMPLE_FLAG_SYNC) != 0
                                ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0;
                        muxer.writeSampleData(muxTrack, buffer, info);
                        if (muxTrack == muxVideo) videoSamples++; else audioSamples++;
                    }
                }
                if (!extractor.advance()) break;
            }

            // Without a single video sample the file is only its audio: better
            // to report a failure than to hand that over (and lose the source).
            if (videoSamples == 0) throw new Exception("No video in the cut");
            muxer.stop();
            muxing = false;
            Log.i(TAG, "cut: " + videoSamples + " video, " + audioSamples
                    + " audio samples from " + baseUs + "us");
            muxer.release();
            muxer = null;

            if (scoped) {
                ContentValues done = new ContentValues();
                done.put(MediaStore.MediaColumns.IS_PENDING, 0);
                try {
                    context.getContentResolver().update(outputUri, done, null, null);
                } catch (Exception ignored) { }
                try { descriptor.close(); } catch (Exception ignored) { }
                descriptor = null;
                return displayNameOf(context, outputUri, name);
            } else {
                MediaScannerConnection.scanFile(context,
                        new String[]{legacyFile.getAbsolutePath()}, new String[]{"video/mp4"},
                        null);
                return legacyFile.getName();
            }
        } catch (Exception e) {
            if (muxing && muxer != null) {
                try { muxer.stop(); } catch (Exception ignored) { }
            }
            try { if (muxer != null) muxer.release(); } catch (Exception ignored) { }
            try { if (descriptor != null) descriptor.close(); } catch (Exception ignored) { }
            if (outputUri != null) {
                try { context.getContentResolver().delete(outputUri, null, null); }
                catch (Exception ignored) { }
            }
            if (legacyFile != null) {
                try { if (legacyFile.exists()) legacyFile.delete(); } catch (Exception ignored) { }
            }
            throw e;
        } finally {
            extractor.release();
        }
    }

    /** The row MediaStore made for the cut; "Download" needs its own collection. */
    private static Uri insertPending(Context context, String name, String folder) {
        boolean downloads = folder.equalsIgnoreCase("Download")
                || folder.toLowerCase(Locale.US).startsWith("download/");
        Uri collection = downloads
                ? MediaStore.Downloads.EXTERNAL_CONTENT_URI
                : MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        values.put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4");
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, folder + "/");
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);
        try {
            return context.getContentResolver().insert(collection, values);
        } catch (Exception e) {
            Log.w(TAG, "Could not create the cut in " + folder, e);
            return null;
        }
    }

    /** MediaStore may add "(1)" when the name is taken; the row shows the real one. */
    private static String displayNameOf(Context context, Uri uri, String fallback) {
        android.database.Cursor cursor = null;
        try {
            cursor = context.getContentResolver().query(uri,
                    new String[]{MediaStore.MediaColumns.DISPLAY_NAME}, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                String name = cursor.getString(0);
                if (name != null && !name.isEmpty()) return name;
            }
        } catch (Exception ignored) {
        } finally {
            try { if (cursor != null) cursor.close(); } catch (Exception ignored) { }
        }
        return fallback;
    }

    private static String describe(Exception e) {
        String message = e.getMessage();
        return message == null || message.isEmpty() ? e.toString() : message;
    }
}
