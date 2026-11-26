package com.bkggds.denrecorder;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionConfig;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;

/**
 * Invisible activity used by the Quick Settings tile: it shows only Android's
 * own capture-consent dialog on top of whatever app the user is in, then hands
 * the result to RecorderService and disappears again.
 */
public class CapturePromptActivity extends Activity {

    private static final String TAG = "CapturePrompt";
    private static final int REQ_PROJECTION = 2001;
    private boolean promptLaunched;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (RecorderService.isRecording) {
            QsTileService.promptFinished();
            finish();
            overridePendingTransition(0, 0);
            return;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (getWindow().getDecorView().hasWindowFocus()) launchCapturePrompt();
    }

    @Override
    protected void onDestroy() {
        if (isFinishing()) QsTileService.promptFinished();
        super.onDestroy();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) launchCapturePrompt();
    }

    private void launchCapturePrompt() {
        if (isFinishing() || promptLaunched) return;
        promptLaunched = true;
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
        } catch (Exception e) {
            QsTileService.promptFinished();
            finish();
            overridePendingTransition(0, 0);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PROJECTION) return;
        QsTileService.promptFinished();

        if (resultCode == RESULT_OK && data != null) {
            SharedPreferences prefs = getSharedPreferences("settings", MODE_PRIVATE);
            int audio = prefs.getInt("audio", ScreenRecorder.AUDIO_INTERNAL);
            if (audio == ScreenRecorder.AUDIO_INTERNAL && Build.VERSION.SDK_INT < 29) {
                // No playback capture before Android 10: use the microphone instead.
                audio = ScreenRecorder.AUDIO_MIC;
            }
            if (audio == ScreenRecorder.AUDIO_INTERNAL_MIC && Build.VERSION.SDK_INT < 29) {
                // The device sound cannot be captured there, so keep the microphone.
                audio = ScreenRecorder.AUDIO_MIC;
            }
            if ((audio == ScreenRecorder.AUDIO_MIC
                    || audio == ScreenRecorder.AUDIO_INTERNAL_MIC)
                    && !hasPermission(android.Manifest.permission.RECORD_AUDIO)) {
                // Without the microphone the device sound is still worth keeping.
                audio = audio == ScreenRecorder.AUDIO_INTERNAL_MIC
                        && Build.VERSION.SDK_INT >= 29
                        ? ScreenRecorder.AUDIO_INTERNAL
                        : ScreenRecorder.AUDIO_NONE;
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
            } catch (Exception e) {
                Log.e(TAG, "Could not start the foreground recorder service", e);
            }
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
