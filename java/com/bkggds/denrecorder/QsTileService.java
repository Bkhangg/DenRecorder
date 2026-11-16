package com.bkggds.denrecorder;

import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/**
 * Control-center tile: tap to start a recording straight from Quick Settings,
 * tap again to stop it.
 */
public class QsTileService extends TileService {

    @Override
    protected void attachBaseContext(android.content.Context newBase) {
        super.attachBaseContext(LocaleHelper.wrap(newBase));
    }

    private final android.os.Handler handler =
            new android.os.Handler(android.os.Looper.getMainLooper());

    @Override
    public void onStartListening() {
        super.onStartListening();
        updateTile();
    }

    @Override
    public void onTileAdded() {
        super.onTileAdded();
        updateTile();
    }

    @Override
    public void onClick() {
        super.onClick();

        if (RecorderService.isRecording) {
            RecorderService.requestStop(this);
            applyState(false); // stopping takes a moment: show it right away
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    updateTile();
                }
            }, 1500);
            return;
        }

        Intent intent = new Intent(this, CapturePromptActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                PendingIntent pending = PendingIntent.getActivity(this, 0, intent,
                        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
                startActivityAndCollapse(pending);
            } else {
                startActivityAndCollapse(intent);
            }
        } catch (Exception e) {
            try {
                startActivity(intent);
            } catch (Exception ignored) { }
        }
        updateTile();
    }

    private void updateTile() {
        applyState(RecorderService.isRecording);
    }

    private void applyState(boolean recording) {
        try {
            Tile tile = getQsTile();
            if (tile == null) return;
            tile.setState(recording ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
            tile.setLabel(recording ? getString(R.string.tile_recording) : getString(R.string.app_name));
            tile.setContentDescription(recording
                    ? getString(R.string.tile_stop_desc)
                    : getString(R.string.tile_start_desc));
            tile.updateTile();
        } catch (Exception ignored) { }
    }
}
