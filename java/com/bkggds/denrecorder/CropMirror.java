package com.bkggds.denrecorder;

import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.projection.MediaProjection;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Records a part of the screen.
 *
 * A VirtualDisplay can only mirror the whole display, so the screen is mirrored
 * into a SurfaceTexture and the chosen rectangle of it is drawn with OpenGL onto
 * the encoder's surface. The video then holds just that part of the screen, at
 * the size the encoder was set up for.
 *
 * Everything happens on one thread of its own ("rec-crop"): the frames arrive
 * there, are drawn there and the display is created there too.
 */
final class CropMirror {

    private static final String TAG = "CropMirror";

    private static final int EGL_RECORDABLE_ANDROID = 0x3142;

    private static final String VERTEX_SHADER =
            "attribute vec4 aPosition;\n"
                    + "attribute vec4 aTexCoord;\n"
                    + "uniform mat4 uTexMatrix;\n"
                    + "varying vec2 vTexCoord;\n"
                    + "void main() {\n"
                    + "  gl_Position = aPosition;\n"
                    + "  vTexCoord = (uTexMatrix * aTexCoord).xy;\n"
                    + "}\n";

    private static final String FRAGMENT_SHADER =
            "#extension GL_OES_EGL_image_external : require\n"
                    + "precision mediump float;\n"
                    + "varying vec2 vTexCoord;\n"
                    + "uniform samplerExternalOES sTexture;\n"
                    + "void main() {\n"
                    + "  gl_FragColor = texture2D(sTexture, vTexCoord);\n"
                    + "}\n";

    private final MediaProjection projection;
    private final Rect crop;           // in screen pixels
    private final int screenWidth;
    private final int screenHeight;
    private final int dpi;
    private final Surface encoderSurface;
    private final int outWidth;
    private final int outHeight;
    private final long frameIntervalNs;

    private HandlerThread thread;
    private Handler handler;
    private SurfaceTexture texture;
    private Surface textureSurface;
    private VirtualDisplay display;

    private EGLDisplay eglDisplay = EGL14.EGL_NO_DISPLAY;
    private EGLContext eglContext = EGL14.EGL_NO_CONTEXT;
    private EGLSurface eglSurface = EGL14.EGL_NO_SURFACE;
    private int program;
    private int textureId;
    private int aPosition;
    private int aTexCoord;
    private int uTexMatrix;
    private int sTexture;
    private FloatBuffer vertexBuffer;
    private FloatBuffer texBuffer;
    private final float[] texMatrix = new float[16];

    private final CountDownLatch ready = new CountDownLatch(1);
    private volatile boolean running;
    private volatile boolean paused;
    private long lastDrawNs;
    private boolean loggedMatrix;

    CropMirror(MediaProjection projection, Rect crop, int screenWidth, int screenHeight,
               int dpi, Surface encoderSurface, int outWidth, int outHeight, int fps) {
        this.projection = projection;
        this.crop = crop;
        this.screenWidth = screenWidth;
        this.screenHeight = screenHeight;
        this.dpi = dpi;
        this.encoderSurface = encoderSurface;
        this.outWidth = outWidth;
        this.outHeight = outHeight;
        this.frameIntervalNs = 1_000_000_000L / Math.max(1, fps);
    }

    /** Draws nothing while paused; the frames that do arrive are still freed. */
    void setPaused(boolean on) {
        paused = on;
    }

    void start() {
        running = true;
        thread = new HandlerThread("rec-crop", android.os.Process.THREAD_PRIORITY_DISPLAY);
        thread.start();
        handler = new Handler(thread.getLooper());
        handler.post(this::setup);
        try {
            if (!ready.await(3, TimeUnit.SECONDS)) Log.w(TAG, "Mirror did not start in time");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    void stop() {
        running = false;
        Handler current = handler;
        if (current != null) {
            final CountDownLatch done = new CountDownLatch(1);
            current.post(() -> {
                try {
                    release();
                } finally {
                    done.countDown();
                }
            });
            try {
                done.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (thread != null) {
            thread.quitSafely();
            try {
                thread.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            thread = null;
        }
        handler = null;
    }

    // ---------- the GL thread ----------

    private void setup() {
        try {
            setUpGl();
            setUpMirror();
            Log.i(TAG, "Cropping " + crop.width() + "x" + crop.height()
                    + " out of " + screenWidth + "x" + screenHeight
                    + " into " + outWidth + "x" + outHeight);
        } catch (Throwable e) {
            Log.w(TAG, "Crop mirror could not start", e);
            release();
        } finally {
            ready.countDown();
        }
    }

    private void setUpGl() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) throw new RuntimeException("no EGL display");
        int[] version = new int[2];
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
            throw new RuntimeException("eglInitialize failed");
        }

        int[] attribs = {
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL_RECORDABLE_ANDROID, 1,
                EGL14.EGL_NONE
        };
        EGLConfig[] configs = new EGLConfig[1];
        int[] count = new int[1];
        if (!EGL14.eglChooseConfig(eglDisplay, attribs, 0, configs, 0, 1, count, 0)
                || count[0] < 1) {
            throw new RuntimeException("no EGL config");
        }

        int[] contextAttribs = {EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE};
        eglContext = EGL14.eglCreateContext(eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT,
                contextAttribs, 0);
        if (eglContext == EGL14.EGL_NO_CONTEXT) throw new RuntimeException("no EGL context");

        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, configs[0], encoderSurface,
                new int[]{EGL14.EGL_NONE}, 0);
        if (eglSurface == EGL14.EGL_NO_SURFACE) throw new RuntimeException("no EGL surface");

        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            throw new RuntimeException("eglMakeCurrent failed");
        }

        program = buildProgram();
        aPosition = GLES20.glGetAttribLocation(program, "aPosition");
        aTexCoord = GLES20.glGetAttribLocation(program, "aTexCoord");
        uTexMatrix = GLES20.glGetUniformLocation(program, "uTexMatrix");
        sTexture = GLES20.glGetUniformLocation(program, "sTexture");

        int[] textures = new int[1];
        GLES20.glGenTextures(1, textures, 0);
        textureId = textures[0];
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

        buildQuad();
    }

    /** The quad covers the whole encoder frame; its texture part is the crop. */
    private void buildQuad() {
        float w = Math.max(1, screenWidth);
        float h = Math.max(1, screenHeight);
        // The producer hands the screen over flipped vertically (sy = -1), so
        // texture row 0 is the screen's top row. The quad's bottom edge is GL
        // v = 0, which therefore reads the crop's last row: measure both edges
        // from the bottom of the screen, or the video starts too far down.
        float u0 = crop.left / w;
        float u1 = crop.right / w;
        float v0 = 1f - crop.bottom / h;
        float v1 = 1f - crop.top / h;

        float[] corners = {-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f};
        float[] tex = {u0, v0, u1, v0, u0, v1, u1, v1};
        vertexBuffer = floats(corners);
        texBuffer = floats(tex);
    }

    private static FloatBuffer floats(float[] values) {
        ByteBuffer buffer = ByteBuffer.allocateDirect(values.length * 4)
                .order(ByteOrder.nativeOrder());
        FloatBuffer out = buffer.asFloatBuffer();
        out.put(values);
        out.position(0);
        return out;
    }

    private void setUpMirror() {
        texture = new SurfaceTexture(textureId);
        texture.setDefaultBufferSize(screenWidth, screenHeight);
        texture.setOnFrameAvailableListener(surfaceTexture -> drawFrame(), handler);
        textureSurface = new Surface(texture);

        display = projection.createVirtualDisplay("DenRecorderCrop",
                screenWidth, screenHeight, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                textureSurface, null, handler);
    }

    private void drawFrame() {
        if (!running) return;
        SurfaceTexture current = texture;
        if (current == null) return;
        try {
            current.updateTexImage();
        } catch (Exception e) {
            return;
        }
        if (paused || eglSurface == EGL14.EGL_NO_SURFACE) return;

        long now = System.nanoTime();
        // The screen redraws faster than some recordings ask for; skipping a
        // frame here costs nothing, the next one is a fraction of a second away.
        if (now - lastDrawNs < frameIntervalNs - frameIntervalNs / 8) return;
        lastDrawNs = now;

        try {
            current.getTransformMatrix(texMatrix);
            if (!loggedMatrix) {
                loggedMatrix = true;
                Log.i(TAG, "texMatrix sx=" + texMatrix[0] + " sy=" + texMatrix[5]
                        + " tx=" + texMatrix[12] + " ty=" + texMatrix[13]
                        + " crop=" + crop.left + "," + crop.top + "," + crop.right + ","
                        + crop.bottom + " screen=" + screenWidth + "x" + screenHeight);
            }

            GLES20.glViewport(0, 0, outWidth, outHeight);
            GLES20.glUseProgram(program);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId);
            GLES20.glUniform1i(sTexture, 0);
            GLES20.glUniformMatrix4fv(uTexMatrix, 1, false, texMatrix, 0);

            vertexBuffer.position(0);
            GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer);
            GLES20.glEnableVertexAttribArray(aPosition);
            texBuffer.position(0);
            GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, 0, texBuffer);
            GLES20.glEnableVertexAttribArray(aTexCoord);

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            GLES20.glDisableVertexAttribArray(aPosition);
            GLES20.glDisableVertexAttribArray(aTexCoord);

            EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, current.getTimestamp());
            if (!EGL14.eglSwapBuffers(eglDisplay, eglSurface)) {
                Log.w(TAG, "eglSwapBuffers failed: " + EGL14.eglGetError());
            }
        } catch (Exception e) {
            Log.w(TAG, "Frame dropped", e);
        }
    }

    private static int buildProgram() {
        int vertex = compile(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER);
        int fragment = compile(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER);
        int program = GLES20.glCreateProgram();
        GLES20.glAttachShader(program, vertex);
        GLES20.glAttachShader(program, fragment);
        GLES20.glLinkProgram(program);
        int[] status = new int[1];
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0);
        if (status[0] != GLES20.GL_TRUE) {
            throw new RuntimeException("Shader link failed: "
                    + GLES20.glGetProgramInfoLog(program));
        }
        GLES20.glDeleteShader(vertex);
        GLES20.glDeleteShader(fragment);
        return program;
    }

    private static int compile(int type, String source) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);
        int[] status = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0);
        if (status[0] != GLES20.GL_TRUE) {
            throw new RuntimeException("Shader compile failed: "
                    + GLES20.glGetShaderInfoLog(shader));
        }
        return shader;
    }

    private void release() {
        running = false;
        try {
            if (display != null) display.release();
        } catch (Exception ignored) { }
        display = null;

        try {
            if (texture != null) {
                texture.setOnFrameAvailableListener(null);
                texture.release();
            }
        } catch (Exception ignored) { }
        texture = null;
        try {
            if (textureSurface != null) textureSurface.release();
        } catch (Exception ignored) { }
        textureSurface = null;

        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            try {
                EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE,
                        EGL14.EGL_NO_CONTEXT);
                if (eglSurface != EGL14.EGL_NO_SURFACE) {
                    EGL14.eglDestroySurface(eglDisplay, eglSurface);
                }
                if (eglContext != EGL14.EGL_NO_CONTEXT) {
                    EGL14.eglDestroyContext(eglDisplay, eglContext);
                }
                EGL14.eglReleaseThread();
                EGL14.eglTerminate(eglDisplay);
            } catch (Exception ignored) { }
        }
        eglSurface = EGL14.EGL_NO_SURFACE;
        eglContext = EGL14.EGL_NO_CONTEXT;
        eglDisplay = EGL14.EGL_NO_DISPLAY;

        if (program != 0) {
            try { GLES20.glDeleteProgram(program); } catch (Exception ignored) { }
            program = 0;
        }
        if (textureId != 0) {
            try { GLES20.glDeleteTextures(1, new int[]{textureId}, 0); } catch (Exception ignored) { }
            textureId = 0;
        }
    }
}
