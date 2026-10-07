package com.lpnovi.radiation.debug;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;

/**
 * Debug-only fake media player, driven over adb:
 *
 *   adb shell am broadcast -n com.lpnovi.radiation/.debug.DebugPlayer --es cmd start|next|prev|toggle|kill
 *
 * A MediaSessionCompat, like Spotify's, so shuffle works end to end. Mimics Spotify's habit of
 * publishing a new track's text first and its artwork ~400ms later, and cycles through
 * deliberately awkward artwork: colorful, near-black, near-white, monochrome, and none at all.
 */
public class DebugPlayer extends BroadcastReceiver {

    private static final String[] TITLES = {"Locked Away (feat. Adam Levine)", "Very dark", "Very bright",
            "Monochrome", "No artwork"};
    private static final int[][] ART = {
            {0xFFE63946, 0xFF457B9D}, {0xFF05060A, 0xFF0D1020}, {0xFFFFFDF5, 0xFFF1ECE0},
            {0xFF8A8A8A, 0xFF2E2E2E}, null,
    };
    private static final long LATE_ART_MS = 400;

    private static final Handler main = new Handler(Looper.getMainLooper());
    private static MediaSessionCompat session;
    private static int index;
    private static boolean playing;

    @Override
    public void onReceive(Context context, Intent intent) {
        String cmd = intent.getStringExtra("cmd");
        if (cmd == null) return;
        Context app = context.getApplicationContext();
        switch (cmd) {
            case "start": ensure(app); playing = true; show(index); break;
            case "next": ensure(app); show(index + 1); break;
            case "prev": ensure(app); show(index - 1); break;
            case "toggle": ensure(app); playing = !playing; state(); break;
            case "kill":
                if (session != null) {
                    session.release();
                    session = null;
                }
                break;
            default: break;
        }
    }

    private static void ensure(Context app) {
        if (session != null) return;
        session = new MediaSessionCompat(app, "RadiationDebugPlayer");
        session.setCallback(new MediaSessionCompat.Callback() {
            @Override public void onPlay() { playing = true; state(); }
            @Override public void onPause() { playing = false; state(); }
            @Override public void onSkipToNext() { show(index + 1); }
            @Override public void onSkipToPrevious() { show(index - 1); }
            @Override public void onSetShuffleMode(int mode) { session.setShuffleMode(mode); }
        }, main);
        session.setShuffleMode(PlaybackStateCompat.SHUFFLE_MODE_NONE);
        session.setActive(true);
        playing = true;
    }

    private static void show(int i) {
        index = Math.floorMod(i, TITLES.length);
        final int shown = index;
        MediaMetadataCompat.Builder text = new MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, TITLES[shown])
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, "Radiation Debug Player")
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, "Track " + (shown + 1));
        session.setMetadata(text.build());
        state();
        if (ART[shown] != null) {
            main.postDelayed(() -> {
                if (session != null && index == shown) {
                    session.setMetadata(text.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART,
                            art(ART[shown])).build());
                }
            }, LATE_ART_MS);
        }
    }

    private static void state() {
        if (session == null) return;
        session.setPlaybackState(new PlaybackStateCompat.Builder()
                .setState(playing ? PlaybackStateCompat.STATE_PLAYING : PlaybackStateCompat.STATE_PAUSED, 0, 1f)
                .setActions(PlaybackStateCompat.ACTION_PLAY | PlaybackStateCompat.ACTION_PAUSE
                        | PlaybackStateCompat.ACTION_PLAY_PAUSE | PlaybackStateCompat.ACTION_SET_SHUFFLE_MODE
                        | PlaybackStateCompat.ACTION_SKIP_TO_NEXT | PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS)
                .build());
    }

    private static Bitmap art(int[] colors) {
        Bitmap b = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888);
        Paint p = new Paint();
        p.setShader(new LinearGradient(0, 0, 512, 512, colors[0], colors[1], Shader.TileMode.CLAMP));
        new Canvas(b).drawRect(0, 0, 512, 512, p);
        return b;
    }
}
