package com.lpnovi.radiation.media;

import android.content.Context;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.os.Handler;
import android.support.v4.media.session.MediaControllerCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shuffle, where the player supports it.
 *
 * The framework media API has no notion of shuffle; it lives in the AndroidX media-compat session
 * protocol, which MediaSessionCompat and Media3 players (Spotify, YouTube Music, most modern apps)
 * implement. Reading it requires a compat controller that has finished an async handshake with the
 * session, so {@link MediaListenerService} keeps one per session and records modes here. A player
 * that never completes the handshake (a plain framework session) stays {@link #UNSUPPORTED}, and
 * the widget shows shuffle as unavailable rather than sending commands it would ignore.
 */
public final class Shuffle {

    public static final int UNSUPPORTED = -1;
    public static final int OFF = 0;
    public static final int ON = 1;

    private static final Map<MediaSession.Token, Integer> modes = new ConcurrentHashMap<>();

    private Shuffle() {}

    public static int of(MediaController controller) {
        Integer mode = modes.get(controller.getSessionToken());
        return mode == null ? UNSUPPORTED : mode;
    }

    /** Toggles shuffle; does nothing for players that don't support it. */
    public static void toggle(Context context, MediaController controller) {
        int mode = of(controller);
        if (mode == UNSUPPORTED) return;
        compat(context, controller).getTransportControls().setShuffleMode(mode == ON
                ? PlaybackStateCompat.SHUFFLE_MODE_NONE : PlaybackStateCompat.SHUFFLE_MODE_ALL);
    }

    static int fromCompat(int compatMode) {
        switch (compatMode) {
            case PlaybackStateCompat.SHUFFLE_MODE_NONE: return OFF;
            case PlaybackStateCompat.SHUFFLE_MODE_ALL:
            case PlaybackStateCompat.SHUFFLE_MODE_GROUP: return ON;
            default: return UNSUPPORTED;
        }
    }

    static MediaControllerCompat compat(Context context, MediaController controller) {
        return new MediaControllerCompat(context, MediaSessionCompat.Token.fromToken(controller.getSessionToken()));
    }

    /** A compat controller that records the session's shuffle mode and reports changes. */
    static final class Watch {
        private final MediaSession.Token token;
        private final MediaControllerCompat compat;
        private final MediaControllerCompat.Callback callback;

        Watch(Context context, MediaController controller, Handler handler, Runnable onChange) {
            token = controller.getSessionToken();
            compat = compat(context, controller);
            callback = new MediaControllerCompat.Callback() {
                @Override
                public void onSessionReady() {
                    record();
                    onChange.run();
                }

                @Override
                public void onShuffleModeChanged(int shuffleMode) {
                    modes.put(token, fromCompat(shuffleMode));
                    onChange.run();
                }
            };
            compat.registerCallback(callback, handler);
            if (compat.isSessionReady()) record();
        }

        private void record() {
            modes.put(token, fromCompat(compat.getShuffleMode()));
        }

        /** Keeps the recorded mode: the same session is usually re-watched straight away. */
        void release() {
            compat.unregisterCallback(callback);
        }
    }

    /** Forgets sessions that no longer exist. */
    static void retainOnly(java.util.Collection<MediaSession.Token> live) {
        modes.keySet().retainAll(live);
    }
}
