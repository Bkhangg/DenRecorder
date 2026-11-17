package com.bkggds.denrecorder;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionConfig;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;

/**
 * Invisible activity used by the Quick Settings tile: it shows only Android's
 * own capture-consent dialog on top of whatever app the user is in, then hands
 * the result to RecorderService and disappears again.
 */
public class CapturePromptActivity extends Activity {

    private static final int REQ_PROJECTION = 2001;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (RecorderService.isRecording) {
            finish();
            overridePendingTransition(0, 0);
            return;
        }
        MediaProjectionManager manager =
                (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        Intent capture;
        if (Build.VERSION.SDK_INT >= 34) {
            try {
                capture = manager.createScreenCaptureIntent(
                        MediaProjectionConfig.createConfigForDefaultDisplay());
            } catch (Exception e) {
                capture = manager.createScreenCaptureIntent();
            }
        } else {
            capture = manager.createScreenCaptureIntent();
        }
        try {
            startActivityForResult(capture, REQ_PROJECTION);
            FastStart.arm(this);
        } catch (Exception e) {
            finish();
            overridePendingTransition(0, 0);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PROJECTION) return;

        if (resultCode == RESULT_OK && data != null) {
            SharedPreferences prefs = getSharedPreferences("settings", MODE_PRIVATE);
            int audio = prefs.getInt("audio", ScreenRecorder.AUDIO_INTERNAL);
            if (audio == ScreenRecorder.AUDIO_INTERNAL && Build.VERSION.SDK_INT < 29) {
                // No playback capture before Android 10: use the microphone instead.
                audio = ScreenRecorder.AUDIO_MIC;
            }
            if (audio == ScreenRecorder.AUDIO_MIC && !hasPermission(
                    android.Manifest.permission.RECORD_AUDIO)) {
                audio = ScreenRecorder.AUDIO_NONE;
            }
            Intent service = new Intent(this, RecorderService.class)
                    .setAction(RecorderService.ACTION_START)
                    .putExtra(RecorderService.EXTRA_RESULT_CODE, resultCode)
                    .putExtra(RecorderService.EXTRA_DATA, data)
                    .putExtra(RecorderService.EXTRA_AUDIO, audio);
            try {
                if (Build.VERSION.SDK_INT >= 26) {
                    startForegroundService(service);
                } else {
                    startService(service);
                }
            } catch (Exception ignored) { }
        }
        finish();
        overridePendingTransition(0, 0);
    }

    private boolean hasPermission(String permission) {
        if (Build.VERSION.SDK_INT < 23) {
            return getPackageManager().checkPermission(permission, getPackageName())
                    == PackageManager.PERMISSION_GRANTED;
        }
        return checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }
}
