package com.bkggds.denrecorder;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.MediaRecorder;
import android.media.MediaScannerConnection;
import android.media.projection.MediaProjection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.Range;
import android.view.Surface;
import android.view.WindowManager;

import java.io.File;
import java.io.FileDescriptor;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Records the screen with MediaCodec: H.264/H.265 video from a VirtualDisplay surface
 * plus AAC audio (microphone or internal playback capture), muxed into an MP4.
 */
public class ScreenRecorder {

    private static final String TAG = "ScreenRecorder";

    public static final int AUDIO_NONE = 0;
    public static final int AUDIO_MIC = 1;
    public static final int AUDIO_INTERNAL = 2;

    public static final String CODEC_AVC = "video/avc";
    public static final String CODEC_HEVC = "video/hevc";
    private static final String MIME_AUDIO = MediaFormat.MIMETYPE_AUDIO_AAC;

    private static final int VIDEO_BITRATE = 32_000_000; // 32 Mbps
    private static final int TARGET_FPS = 60;
    private static final int AUDIO_BITRATE = 192_000;
    private static final int SAMPLE_RATE = 48_000;
    private static final int CHANNELS = 2;
    public static final String DEFAULT_FOLDER = "Movies/Screen Recorder";

    private final Context context;
    private final MediaProjection projection;
    private final int audioSource;
    private final int requestedWidth;   // 0 = native
    private final int requestedFps;
    private final int requestedBitrate;
    private final String mime;
    private final String orientation;   // "auto", "portrait" or "landscape"
    private String codecLabel = "AVC";

    private MediaCodec videoCodec;
    private MediaCodec audioCodec;
    private Surface inputSurface;
    private VirtualDisplay virtualDisplay;
    private AudioRecord audioRecord;
    private MediaMuxer muxer;
    private ParcelFileDescriptor outputFd;
    private Uri outputUri;
    /** Set on Android 9 and older, where the video is a plain file on the card. */
    private File legacyFile;
    private boolean legacyOutput;

    private final Object muxLock = new Object();
    private boolean muxStarted;
    private boolean muxStartedEver;
    private int videoTrack = -1;
    private int audioTrack = -1;
    private boolean videoTrackAdded;
    private boolean audioTrackAdded;
    private volatile boolean audioActive;
    private int writtenSamples;
    private String outputName;

    private Thread videoThread;
    private Thread audioThread;
    private volatile boolean stopping;
    private volatile boolean started;

    /** Pause drops encoded frames and closes the timestamp gap on resume. */
    private volatile boolean paused;
    private volatile long pauseStartedUs;
    private final java.util.concurrent.atomic.AtomicLong pausedUs =
            new java.util.concurrent.atomic.AtomicLong(0);

    private int width;
    private int height;
    private int fps;
    private int videoBitrate = VIDEO_BITRATE;
    private long startNs;
    private long lastVideoPts = -1;
    private final java.util.concurrent.atomic.AtomicLong audioFrames =
            new java.util.concurrent.atomic.AtomicLong(0);
    /** The video went into MediaStore's downloads collection (Download folder). */
    private boolean outputInDownloads;

    public ScreenRecorder(Context context, MediaProjection projection, int audioSource) {
        this(context, projection, audioSource, 0, TARGET_FPS, VIDEO_BITRATE, CODEC_AVC, "auto");
    }

    public ScreenRecorder(Context context, MediaProjection projection, int audioSource,
                          int requestedWidth, int requestedFps, int requestedBitrate,
                          String mime) {
        this(context, projection, audioSource, requestedWidth, requestedFps, requestedBitrate,
                mime, "auto");
    }

    /**
     * @param orientation "portrait" or "landscape" when the recording is locked
     *                    to that rotation, "auto" to follow the display.
     */
    public ScreenRecorder(Context context, MediaProjection projection, int audioSource,
                          int requestedWidth, int requestedFps, int requestedBitrate,
                          String mime, String orientation) {
        this.orientation = orientation == null ? "auto" : orientation;
        this.context = context;
        this.projection = projection;
        this.audioSource = audioSource;
        this.requestedWidth = requestedWidth;
        this.requestedFps = requestedFps > 0 ? requestedFps : TARGET_FPS;
        this.requestedBitrate = requestedBitrate > 0 ? requestedBitrate : VIDEO_BITRATE;
        this.mime = CODEC_HEVC.equals(mime) ? CODEC_HEVC : CODEC_AVC;
        this.codecLabel = this.mime.equals(CODEC_HEVC) ? "HEVC" : "AVC";
    }

    /** Stops adding frames to the video without ending it. */
    public void pause() {
        if (paused || stopping || !started) return;
        pauseStartedUs = SystemClock.elapsedRealtime() * 1000L;
        paused = true;
        Log.i(TAG, "Paused at " + pauseStartedUs / 1000 + " ms");
    }

    /** Continues the video where it was, with a fresh key frame. */
    public void resume() {
        if (!paused || stopping) return;
        long now = SystemClock.elapsedRealtime() * 1000L;
        if (now > pauseStartedUs) pausedUs.addAndGet(now - pauseStartedUs);
        paused = false;
        Log.i(TAG, "Resumed, paused so far " + pausedUs.get() / 1000 + " ms");
        requestSyncFrame();
    }

    public boolean isPaused() {
        return paused;
    }

    private void requestSyncFrame() {
        try {
            android.os.Bundle params = new android.os.Bundle();
            params.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
            if (videoCodec != null) videoCodec.setParameters(params);
        } catch (Exception e) {
            Log.w(TAG, "Could not ask for a key frame", e);
        }
    }

    public Uri getOutputUri() {
        return outputUri;
    }

    public String getOutputName() {
        return outputName;
    }

    public long getElapsedMs() {
        if (startNs == 0) return 0;
        return (System.nanoTime() - startNs) / 1_000_000L;
    }

    public String describeSettings() {
        return width + "×" + height + " · " + fps + " fps · "
                + (videoBitrate / 1_000_000) + " Mbps · " + codecLabel;
    }

    public void start() throws Exception {
        DisplayMetrics dm = new DisplayMetrics();
        WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        wm.getDefaultDisplay().getRealMetrics(dm);
        int dpi = dm.densityDpi;

        // With a locked rotation the screen keeps one way round; measure the
        // display that way even if the size is read a moment too early.
        int nativeWidth = dm.widthPixels;
        int nativeHeight = dm.heightPixels;
        if (nativeWidth > 0 && nativeHeight > 0) {
            boolean sideways = nativeWidth > nativeHeight;
            if ("landscape".equals(orientation) && !sideways) {
                nativeWidth = dm.heightPixels;
                nativeHeight = dm.widthPixels;
            } else if ("portrait".equals(orientation) && sideways) {
                nativeWidth = dm.heightPixels;
                nativeHeight = dm.widthPixels;
            }
        }

        createOutput();
        prepareVideo(nativeWidth, nativeHeight);
        prepareAudio();

        startNs = System.nanoTime();
        videoCodec.start();
        if (audioActive) {
            audioCodec.start();
            try {
                audioRecord.startRecording();
            } catch (Exception e) {
                Log.w(TAG, "AudioRecord could not start", e);
                audioActive = false;
                try { audioCodec.release(); } catch (Exception ignored) { }
                audioCodec = null;
                try { audioRecord.release(); } catch (Exception ignored) { }
                audioRecord = null;
            }
        }

        virtualDisplay = projection.createVirtualDisplay(
                "ScreenRecorder",
                width, height, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                inputSurface, null, null);

        started = true;
        videoThread = new Thread(this::runVideo, "rec-video");
        videoThread.start();
        if (audioActive) {
            audioThread = new Thread(this::runAudio, "rec-audio");
            audioThread.start();
        }
        Log.i(TAG, "Recording started: " + describeSettings());
    }

    private void createOutput() throws Exception {
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        outputName = "DenRecorder_" + stamp + ".mp4";

        legacyOutput = Build.VERSION.SDK_INT < 29;
        if (legacyOutput) {
            createLegacyOutput();
            return;
        }

        ContentResolver resolver = context.getContentResolver();
        outputUri = insertVideo(resolver, saveFolder());
        if (outputUri == null) {
            // The picked folder is gone (card removed, renamed…): fall back.
            outputUri = insertVideo(resolver, DEFAULT_FOLDER);
        }
        if (outputUri == null) throw new Exception("Could not create the output file");

        outputFd = resolver.openFileDescriptor(outputUri, "rw");
        if (outputFd == null) throw new Exception("Could not open the output file");

        muxer = new MediaMuxer(outputFd.getFileDescriptor(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
    }

    /**
     * Android 9 and older have no scoped storage: the video is a normal file in
     * the public folder, muxed by its path (MediaMuxer took a FileDescriptor only
     * from Android 8), and registered in the gallery afterwards.
     */
    private void createLegacyOutput() throws Exception {
        File dir = publicFolder(saveFolder());
        if (dir == null) dir = publicFolder(DEFAULT_FOLDER);
        if (dir == null) throw new Exception("Could not create the output folder");
        if (!dir.exists() && !dir.mkdirs() && !dir.exists()) {
            throw new Exception("Could not create the output folder");
        }

        File file = new File(dir, outputName);
        try {
            if (!file.exists()) file.createNewFile();
        } catch (Exception e) {
            throw new Exception("Could not create the output file", e);
        }
        legacyFile = file;
        muxer = new MediaMuxer(file.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

        ContentValues values = new ContentValues();
        values.put(MediaStore.Video.Media.DISPLAY_NAME, outputName);
        values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
        values.put(MediaStore.Video.Media.DATA, file.getAbsolutePath());
        values.put(MediaStore.Video.Media.DATE_ADDED, System.currentTimeMillis() / 1000L);
        try {
            outputUri = context.getContentResolver()
                    .insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
        } catch (Exception e) {
            Log.w(TAG, "Could not register the video in MediaStore", e);
            outputUri = null;
        }
    }

    /**
     * The public folder a relative path such as "Movies/Screen Recorder" points
     * to on Android 9 and older, where there is no RELATIVE_PATH column.
     */
    public static File publicFolder(String folder) {
        try {
            String path = folder == null ? DEFAULT_FOLDER : folder;
            path = path.replaceAll("^/+", "").replaceAll("/+$", "");
            String[] parts = path.isEmpty() ? new String[0] : path.split("/");
            String base;
            int start = 1;
            String first = parts.length > 0 ? parts[0] : "";
            if ("DCIM".equalsIgnoreCase(first)) {
                base = Environment.DIRECTORY_DCIM;
            } else if ("Download".equalsIgnoreCase(first)
                    || "Downloads".equalsIgnoreCase(first)) {
                base = Environment.DIRECTORY_DOWNLOADS;
            } else if ("Movies".equalsIgnoreCase(first) || parts.length == 1) {
                base = Environment.DIRECTORY_MOVIES;
            } else {
                base = Environment.DIRECTORY_MOVIES;
                start = 0;
            }
            if (parts.length == 0) start = 1;
            File dir = Environment.getExternalStoragePublicDirectory(base);
            for (int i = start; i < parts.length; i++) {
                if (!parts[i].isEmpty()) dir = new File(dir, parts[i]);
            }
            return dir;
        } catch (Exception e) {
            return null;
        }
    }

    private String saveFolder() {
        String folder = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                .getString("saveFolder", DEFAULT_FOLDER);
        if (folder == null) folder = DEFAULT_FOLDER;
        folder = folder.replaceAll("^/+", "").replaceAll("/+$", "");
        return folder.isEmpty() ? DEFAULT_FOLDER : folder;
    }

    /**
     * Videos live in the video collection, except in Download: MediaStore only
     * takes those through its own downloads collection, so "Save to Download"
     * used to fall back to the default folder.
     */
    private Uri insertVideo(ContentResolver resolver, String folder) {
        boolean downloads = folder.equalsIgnoreCase("Download")
                || folder.toLowerCase(Locale.US).startsWith("download/");
        Uri collection = downloads
                ? MediaStore.Downloads.EXTERNAL_CONTENT_URI
                : MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, outputName);
        values.put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4");
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, folder + "/");
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);
        try {
            Uri uri = resolver.insert(collection, values);
            if (uri != null) outputInDownloads = downloads;
            return uri;
        } catch (Exception e) {
            Log.w(TAG, "Could not create a file in " + folder, e);
            return null;
        }
    }

    private void prepareVideo(int screenWidth, int screenHeight) throws Exception {
        MediaCodecInfo encoder = chooseVideoEncoder(mime);
        MediaCodecInfo.VideoCapabilities caps = null;
        MediaCodecInfo.CodecCapabilities codecCaps = null;
        if (encoder != null) {
            try {
                codecCaps = encoder.getCapabilitiesForType(mime);
                caps = codecCaps.getVideoCapabilities();
            } catch (Exception ignored) { }
        }

        int alignW = 2;
        int alignH = 2;
        if (caps != null) {
            try {
                alignW = Math.max(2, caps.getWidthAlignment());
                alignH = Math.max(2, caps.getHeightAlignment());
            } catch (Exception ignored) { }
        }

        // Sizes to try: exactly what the user picked (only rounded to an even
        // number, so a native recording stays 1220 × 2712), the same size rounded
        // to the encoder's alignment, the native screen, then whatever the encoder
        // claims to support.
        List<int[]> candidates = new ArrayList<>();
        int[] target = requestedWidth > 0
                ? scaledSize(screenWidth, screenHeight, requestedWidth)
                : new int[]{screenWidth & ~1, screenHeight & ~1};
        addCandidate(candidates, target);
        addCandidate(candidates, new int[]{align(target[0], alignW), align(target[1], alignH)});
        addCandidate(candidates, new int[]{screenWidth & ~1, screenHeight & ~1});
        addCandidate(candidates, new int[]{align(screenWidth, alignW), align(screenHeight, alignH)});
        addCandidate(candidates, chooseVideoSize(caps, screenWidth, screenHeight));

        // Frame rates to try: the one picked by the user, then lower ones, so a
        // heavy combination still records instead of failing outright.
        List<Integer> fpsOptions = new ArrayList<>();
        for (int option : new int[]{requestedFps, 60, 30}) {
            if (option > 0 && !fpsOptions.contains(option)) fpsOptions.add(option);
        }

        Exception failure = null;
        for (int[] size : candidates) {
            int candidateW = size[0];
            int candidateH = size[1];

            int candidateBitrate = requestedBitrate;
            boolean vbr = true;
            if (caps != null) {
                try {
                    Range<Integer> bitrates = caps.getBitrateRange();
                    candidateBitrate = Math.max(bitrates.getLower(),
                            Math.min(bitrates.getUpper(), requestedBitrate));
                } catch (Exception ignored) { }
            }
            if (codecCaps != null) {
                try {
                    MediaCodecInfo.EncoderCapabilities enc = codecCaps.getEncoderCapabilities();
                    if (enc != null && !enc.isBitrateModeSupported(
                            MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)) {
                        vbr = false;
                    }
                } catch (Exception ignored) { }
            }

            for (int candidateFps : fpsOptions) {
                MediaCodec codec = null;
                try {
                    codec = encoder != null
                            ? MediaCodec.createByCodecName(encoder.getName())
                            : MediaCodec.createEncoderByType(mime);
                    codec.configure(buildVideoFormat(candidateW, candidateH, candidateFps,
                                    candidateBitrate, vbr), null, null,
                            MediaCodec.CONFIGURE_FLAG_ENCODE);
                } catch (Exception e) {
                    failure = e;
                    Log.w(TAG, "Tuned format rejected for " + candidateW + "x" + candidateH
                            + "@" + candidateFps, e);
                    if (codec != null) {
                        try { codec.release(); } catch (Exception ignored) { }
                    }
                    codec = null;
                    // Retry with the plain format in case the codec dislikes a hint.
                    try {
                        codec = encoder != null
                                ? MediaCodec.createByCodecName(encoder.getName())
                                : MediaCodec.createEncoderByType(mime);
                        MediaFormat plain = MediaFormat.createVideoFormat(mime,
                                candidateW, candidateH);
                        plain.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
                        plain.setInteger(MediaFormat.KEY_BIT_RATE, candidateBitrate);
                        plain.setInteger(MediaFormat.KEY_FRAME_RATE, candidateFps);
                        plain.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
                        codec.configure(plain, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                    } catch (Exception e2) {
                        failure = e2;
                        Log.w(TAG, "Plain format rejected for " + candidateW + "x" + candidateH
                                + "@" + candidateFps, e2);
                        if (codec != null) {
                            try { codec.release(); } catch (Exception ignored) { }
                        }
                        codec = null;
                    }
                }

                if (codec != null) {
                    videoCodec = codec;
                    width = candidateW;
                    height = candidateH;
                    fps = candidateFps;
                    videoBitrate = candidateBitrate;
                    inputSurface = codec.createInputSurface();
                    Log.i(TAG, "Video " + width + "x" + height + " @" + fps + "fps "
                            + videoBitrate / 1_000_000 + "Mbps " + codecLabel + ", encoder "
                            + (encoder == null ? "auto" : encoder.getName()));
                    return;
                }
            }
        }
        throw new Exception("No usable " + codecLabel + " video format: "
                + (failure == null ? "unknown error" : failure.getMessage()));
    }

    /** The native screen scaled to the chosen width, keeping its aspect ratio. */
    private static int[] scaledSize(int screenWidth, int screenHeight, int targetWidth) {
        float scale = (float) targetWidth / Math.max(1, screenWidth);
        int w = Math.round(screenWidth * scale);
        int h = Math.round(screenHeight * scale);
        return limitToPlayable(Math.max(2, w), Math.max(2, h));
    }

    /**
     * Phone decoders stop at 3840 pixels on the long side and at 36864
     * macroblocks per frame (H.264 level 5.1). A taller "4K" clip — 2160 × 4802
     * on a portrait phone — records fine but then will not play in the gallery,
     * so every recording is kept inside that limit.
     */
    public static int[] limitToPlayable(int width, int height) {
        if (width < 2 || height < 2) {
            return new int[]{Math.max(2, width & ~1), Math.max(2, height & ~1)};
        }
        final int maxSide = 3840;
        final long maxMacroblocks = 36864L;
        double scale = 1.0;
        int side = Math.max(width, height);
        if (side > maxSide) scale = (double) maxSide / side;
        long scaledWidth = Math.round(width * scale);
        long scaledHeight = Math.round(height * scale);
        long macroblocks = ((scaledWidth + 15) / 16) * ((scaledHeight + 15) / 16);
        if (macroblocks > maxMacroblocks) {
            double extra = Math.sqrt((double) maxMacroblocks / macroblocks);
            scaledWidth = Math.round(scaledWidth * extra);
            scaledHeight = Math.round(scaledHeight * extra);
        }
        return new int[]{
                (int) Math.max(2, scaledWidth & ~1),
                (int) Math.max(2, scaledHeight & ~1)
        };
    }

    private static void addCandidate(List<int[]> candidates, int[] size) {
        if (size == null || size[0] < 2 || size[1] < 2) return;
        for (int[] existing : candidates) {
            if (existing[0] == size[0] && existing[1] == size[1]) return;
        }
        candidates.add(size);
    }

    private MediaFormat buildVideoFormat(int w, int h, int fps, int bitrate, boolean vbr) {
        MediaFormat format = MediaFormat.createVideoFormat(mime, w, h);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
        if (vbr) {
            format.setInteger(MediaFormat.KEY_BITRATE_MODE,
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR);
        }
        // Let the encoder drop frames instead of stalling the system when the
        // phone is busy (app switching, scrolling), and never encode more than
        // the requested frame rate.
        if (Build.VERSION.SDK_INT >= 23) {
            format.setInteger(MediaFormat.KEY_PRIORITY, 0);
        }
        format.setFloat(MediaFormat.KEY_OPERATING_RATE, (float) fps);
        if (Build.VERSION.SDK_INT >= 29) {
            format.setFloat(MediaFormat.KEY_MAX_FPS_TO_ENCODER, (float) fps);
        }
        return format;
    }

    /**
     * The largest size the encoder accepts, as close to the native screen size as
     * possible. Encoders require their specific width/height alignment, so rounding
     * to an even number is not enough.
     */
    private static int[] chooseVideoSize(MediaCodecInfo.VideoCapabilities caps,
                                         int screenWidth, int screenHeight) {
        int nativeW = Math.max(2, screenWidth);
        int nativeH = Math.max(2, screenHeight);
        if (caps == null) return new int[]{nativeW & ~1, nativeH & ~1};

        int alignW = 2;
        int alignH = 2;
        Range<Integer> widths = null;
        Range<Integer> heights = null;
        try {
            alignW = Math.max(2, caps.getWidthAlignment());
            alignH = Math.max(2, caps.getHeightAlignment());
            widths = caps.getSupportedWidths();
            heights = caps.getSupportedHeights();
        } catch (Exception ignored) { }

        double startScale = 1.0;
        if (widths != null && widths.getUpper() < nativeW) {
            startScale = Math.min(startScale, (double) widths.getUpper() / nativeW);
        }
        if (heights != null && heights.getUpper() < nativeH) {
            startScale = Math.min(startScale, (double) heights.getUpper() / nativeH);
        }

        for (double scale = startScale; scale >= 0.30; scale -= 0.01) {
            int cw = align(Math.round(nativeW * (float) scale), alignW);
            int ch = align(Math.round(nativeH * (float) scale), alignH);
            if (widths != null && (cw > widths.getUpper() || cw < widths.getLower())) continue;
            if (heights != null && (ch > heights.getUpper() || ch < heights.getLower())) continue;
            boolean ok;
            try {
                ok = caps.isSizeSupported(cw, ch);
            } catch (Exception e) {
                ok = true;
            }
            if (ok) return new int[]{cw, ch};
        }

        return new int[]{align(nativeW, alignW), align(nativeH, alignH)};
    }

    private static int align(int value, int alignment) {
        if (alignment <= 2) return value & ~1;
        int aligned = (value / alignment) * alignment;
        return Math.max(alignment, aligned);
    }

    private static MediaCodecInfo chooseVideoEncoder(String mime) {
        MediaCodecInfo best = null;
        int bestScore = Integer.MIN_VALUE;
        try {
            MediaCodecList list = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
            for (MediaCodecInfo info : list.getCodecInfos()) {
                if (!info.isEncoder() || !supportsType(info, mime)) continue;
                MediaCodecInfo.CodecCapabilities caps;
                try {
                    caps = info.getCapabilitiesForType(mime);
                } catch (Exception e) {
                    continue;
                }
                MediaCodecInfo.VideoCapabilities video = caps.getVideoCapabilities();
                if (video == null) continue;

                boolean surfaceInput = false;
                for (int color : caps.colorFormats) {
                    if (color == MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface) {
                        surfaceInput = true;
                        break;
                    }
                }
                if (!surfaceInput) continue;

                int score = 0;
                String name = info.getName().toLowerCase(Locale.US);
                if (!name.startsWith("c2.android") && !name.startsWith("omx.google")) score += 10;
                try {
                    if (Math.max(video.getSupportedWidths().getUpper(),
                            video.getSupportedHeights().getUpper()) >= 2160) score += 5;
                    if (Math.max(video.getSupportedWidths().getUpper(),
                            video.getSupportedHeights().getUpper()) >= 3000) score += 5;
                } catch (Exception ignored) { }
                if (score > bestScore) {
                    bestScore = score;
                    best = info;
                }
            }
        } catch (Exception ignored) { }
        return best;
    }

    private static boolean supportsType(MediaCodecInfo info, String mime) {
        for (String type : info.getSupportedTypes()) {
            if (type.equalsIgnoreCase(mime)) return true;
        }
        return false;
    }

    private void prepareAudio() {
        if (audioSource == AUDIO_NONE) {
            audioActive = false;
            return;
        }
        int minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT);
        if (minBuf <= 0) minBuf = 16_384;
        int bufSize = Math.max(minBuf * 2, 32_768);

        AudioFormat format = new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .build();

        try {
            boolean internal = audioSource == AUDIO_INTERNAL
                    && Build.VERSION.SDK_INT >= 29
                    && projection != null;
            AudioRecord record;
            if (internal) {
                AudioPlaybackCaptureConfiguration capture =
                        new AudioPlaybackCaptureConfiguration.Builder(projection)
                                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                                .build();
                record = new AudioRecord.Builder()
                        .setAudioFormat(format)
                        .setBufferSizeInBytes(bufSize)
                        .setAudioPlaybackCaptureConfig(capture)
                        .build();
            } else if (Build.VERSION.SDK_INT >= 23) {
                // Microphone, and the fallback for "device audio" before Android 10,
                // which has no playback capture.
                record = new AudioRecord.Builder()
                        .setAudioSource(MediaRecorder.AudioSource.MIC)
                        .setAudioFormat(format)
                        .setBufferSizeInBytes(bufSize)
                        .build();
            } else {
                record = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                        AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT, bufSize);
            }
            if (record.getState() != AudioRecord.STATE_INITIALIZED) {
                record.release();
                throw new Exception("Microphone/audio capture unavailable");
            }
            audioRecord = record;

            MediaFormat audioFormat = MediaFormat.createAudioFormat(MIME_AUDIO, SAMPLE_RATE, CHANNELS);
            audioFormat.setInteger(MediaFormat.KEY_AAC_PROFILE,
                    MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            audioFormat.setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BITRATE);
            audioFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384);
            audioCodec = MediaCodec.createEncoderByType(MIME_AUDIO);
            audioCodec.configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            audioActive = true;
        } catch (Exception e) {
            Log.w(TAG, "Audio capture setup failed, recording video only", e);
            audioActive = false;
            try { if (audioRecord != null) audioRecord.release(); } catch (Exception ignored) { }
            audioRecord = null;
            try { if (audioCodec != null) audioCodec.release(); } catch (Exception ignored) { }
            audioCodec = null;
        }
    }

    private void runVideo() {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DISPLAY);
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        long firstPts = -1;
        try {
            while (true) {
                int index = videoCodec.dequeueOutputBuffer(info, 10_000);
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    synchronized (muxLock) {
                        if (!videoTrackAdded) {
                            videoTrack = muxer.addTrack(videoCodec.getOutputFormat());
                            videoTrackAdded = true;
                            maybeStartMuxer();
                        }
                    }
                } else if (index >= 0) {
                    ByteBuffer buffer = videoCodec.getOutputBuffer(index);
                    if (buffer != null && info.size > 0
                            && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        if (firstPts < 0) firstPts = info.presentationTimeUs;
                        if (!paused) {
                            // Frames encoded while paused are dropped; the time
                            // they took is cut out so the video runs on without
                            // a freeze. The muxer also needs growing timestamps.
                            long pts = Math.max(0,
                                    info.presentationTimeUs - firstPts - pausedUs.get());
                            if (pts <= lastVideoPts) pts = lastVideoPts + 1;
                            lastVideoPts = pts;
                            info.presentationTimeUs = pts;
                            buffer.position(info.offset);
                            buffer.limit(info.offset + info.size);
                            synchronized (muxLock) {
                                if (muxStarted) {
                                    muxer.writeSampleData(videoTrack, buffer, info);
                                    writtenSamples++;
                                }
                            }
                        }
                    }
                    videoCodec.releaseOutputBuffer(index, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Video encoder stopped", e);
        }
    }

    private void runAudio() {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        short[] pcm = new short[1024 * CHANNELS];
        long frames = 0;
        audioFrames.set(0);

        while (!stopping) {
            int read;
            try {
                read = audioRecord.read(pcm, 0, pcm.length);
            } catch (Exception e) {
                break;
            }
            if (read <= 0) {
                if (stopping) break;
                SystemClock.sleep(5);
                continue;
            }
            if (paused) {
                // Sound recorded while paused is not kept either.
                drainAudio(info, false);
                continue;
            }
            int index;
            try {
                index = audioCodec.dequeueInputBuffer(10_000);
            } catch (Exception e) {
                break;
            }
            if (index >= 0) {
                ByteBuffer in = audioCodec.getInputBuffer(index);
                if (in != null) {
                    in.clear();
                    int count = Math.min(read, in.remaining() / 2);
                    in.order(ByteOrder.nativeOrder()).asShortBuffer().put(pcm, 0, count);
                    long pts = frames * 1_000_000L / SAMPLE_RATE;
                    audioCodec.queueInputBuffer(index, 0, count * 2, pts, 0);
                    frames += count / CHANNELS;
                    audioFrames.set(frames);
                }
            }
            drainAudio(info, false);
        }

        // Flush with end-of-stream.
        try {
            int index = audioCodec.dequeueInputBuffer(10_000);
            if (index >= 0) {
                long pts = frames * 1_000_000L / SAMPLE_RATE;
                audioCodec.queueInputBuffer(index, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
            }
            long deadline = System.currentTimeMillis() + 3000;
            while (System.currentTimeMillis() < deadline) {
                if (drainAudio(info, true)) break;
            }
        } catch (Exception ignored) { }
    }

    private boolean drainAudio(MediaCodec.BufferInfo info, boolean wait) {
        try {
            while (true) {
                int index = audioCodec.dequeueOutputBuffer(info, wait ? 10_000 : 0);
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    synchronized (muxLock) {
                        if (!audioTrackAdded) {
                            audioTrack = muxer.addTrack(audioCodec.getOutputFormat());
                            audioTrackAdded = true;
                            maybeStartMuxer();
                        }
                    }
                } else if (index >= 0) {
                    ByteBuffer buffer = audioCodec.getOutputBuffer(index);
                    if (buffer != null && info.size > 0
                            && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        buffer.position(info.offset);
                        buffer.limit(info.offset + info.size);
                        synchronized (muxLock) {
                            if (muxStarted) {
                                muxer.writeSampleData(audioTrack, buffer, info);
                                writtenSamples++;
                            }
                        }
                    }
                    audioCodec.releaseOutputBuffer(index, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return true;
                } else {
                    return false;
                }
            }
        } catch (Exception e) {
            return true;
        }
    }

    private void maybeStartMuxer() {
        if (muxStarted) return;
        if (videoTrackAdded && (!audioActive || audioTrackAdded)) {
            muxer.start();
            muxStarted = true;
            muxStartedEver = true;
            Log.i(TAG, "Muxer started");
        }
    }

    public void stop() {
        if (!started) {
            cleanupOutput();
            return;
        }
        stopping = true;
        Log.i(TAG, "Stopping recording");

        try { if (virtualDisplay != null) virtualDisplay.release(); } catch (Exception ignored) { }
        virtualDisplay = null;
        try { if (videoCodec != null) videoCodec.signalEndOfInputStream(); } catch (Exception ignored) { }
        try { if (audioRecord != null) audioRecord.stop(); } catch (Exception ignored) { }

        joinThread(videoThread);
        joinThread(audioThread);
        videoThread = null;
        audioThread = null;

        releaseCodec(videoCodec);
        videoCodec = null;
        releaseCodec(audioCodec);
        audioCodec = null;

        try { if (inputSurface != null) inputSurface.release(); } catch (Exception ignored) { }
        inputSurface = null;
        try { if (audioRecord != null) audioRecord.release(); } catch (Exception ignored) { }
        audioRecord = null;

        synchronized (muxLock) {
            if (muxStarted) {
                try { muxer.stop(); } catch (Exception e) { Log.w(TAG, "Muxer stop failed", e); }
                muxStarted = false;
            }
            try { if (muxer != null) muxer.release(); } catch (Exception ignored) { }
            muxer = null;
        }

        closeOutputFd();
        finalizeOutput();
        started = false;
    }

    void logTimeline() {
        Log.i(TAG, "Timeline: last video pts " + lastVideoPts / 1000
                + " ms, audio " + (audioActive ? audioFrames.get() * 1000L / SAMPLE_RATE : 0)
                + " ms, paused " + pausedUs.get() / 1000 + " ms");
    }

    private void cleanupOutput() {
        closeOutputFd();
        try { if (muxer != null) muxer.release(); } catch (Exception ignored) { }
        muxer = null;
        if (legacyFile != null) {
            try { if (legacyFile.exists()) legacyFile.delete(); } catch (Exception ignored) { }
            legacyFile = null;
        }
        if (outputUri != null) {
            try { context.getContentResolver().delete(outputUri, null, null); } catch (Exception ignored) { }
            outputUri = null;
        }
    }

    private void joinThread(Thread thread) {
        if (thread == null) return;
        try {
            thread.join(4000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void releaseCodec(MediaCodec codec) {
        if (codec == null) return;
        try { codec.stop(); } catch (Exception ignored) { }
        try { codec.release(); } catch (Exception ignored) { }
    }

    private void closeOutputFd() {
        try { if (outputFd != null) outputFd.close(); } catch (Exception ignored) { }
        outputFd = null;
    }

    private void finalizeOutput() {
        if (legacyOutput) {
            finalizeLegacyOutput();
            return;
        }
        if (outputUri == null) return;
        ContentResolver resolver = context.getContentResolver();
        if (!muxStartedEver || writtenSamples == 0) {
            try { resolver.delete(outputUri, null, null); } catch (Exception ignored) { }
            outputUri = null;
            return;
        }
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.IS_PENDING, 0);
        try { resolver.update(outputUri, values, null, null); } catch (Exception ignored) { }
    }

    /** Registers (or refreshes) the finished video in the gallery on Android 9 and older. */
    private void finalizeLegacyOutput() {
        final File file = legacyFile;
        if (file == null) return;
        if (!muxStartedEver || writtenSamples == 0) {
            try { if (file.exists()) file.delete(); } catch (Exception ignored) { }
            if (outputUri != null) {
                try { context.getContentResolver().delete(outputUri, null, null); } catch (Exception ignored) { }
            }
            outputUri = null;
            legacyFile = null;
            return;
        }
        try {
            MediaScannerConnection.scanFile(context,
                    new String[]{file.getAbsolutePath()},
                    new String[]{"video/mp4"},
                    new MediaScannerConnection.OnScanCompletedListener() {
                        @Override
                        public void onScanCompleted(String path, Uri uri) {
                            if (uri != null) outputUri = uri;
                        }
                    });
        } catch (Exception ignored) { }
    }

    /** Duration in ms of a finished recording, if known. */
    public static String formatDuration(long ms) {
        long total = Math.max(0, ms) / 1000L;
        long minutes = total / 60;
        long seconds = total % 60;
        return String.format(Locale.US, "%02d:%02d", minutes, seconds);
    }
}
