package com.lpnovi.radiation.media;

import android.content.ComponentName;
import android.content.Context;
import android.media.AudioManager;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.SystemClock;
import android.view.KeyEvent;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationManagerCompat;

import java.util.List;

/** Finds the media session the widget should reflect and sends transport actions to it. */
public final class MediaSessions {

    public static final String SPOTIFY = "com.spotify.music";

    private MediaSessions() {}

    /** Whether the user has granted notification-listener access, which Android requires for reading sessions. */
    public static boolean hasAccess(Context context) {
        return NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.getPackageName());
    }

    /** The session to show, or null when nothing is available or access is not granted. */
    @Nullable
    public static MediaController active(Context context) {
        List<MediaController> sessions;
        try {
            sessions = context.getSystemService(MediaSessionManager.class)
                    .getActiveSessions(new ComponentName(context, MediaListenerService.class));
        } catch (SecurityException e) {
            return null;
        }
        int[] states = new int[sessions.size()];
        for (int i = 0; i < states.length; i++) states[i] = stateOf(sessions.get(i));
        int i = pick(states);
        return i < 0 ? null : sessions.get(i);
    }

    /**
     * Sessions arrive in the system's priority order (most recently active first). Prefer the first
     * one that is actually playing, otherwise the top one, so a paused Spotify still shows.
     */
    static int pick(int[] states) {
        for (int i = 0; i < states.length; i++) {
            if (states[i] == PlaybackState.STATE_PLAYING) return i;
        }
        return states.length > 0 ? 0 : -1;
    }

    public static boolean isPlaying(@Nullable MediaController controller) {
        return controller != null && stateOf(controller) == PlaybackState.STATE_PLAYING;
    }

    private static int stateOf(MediaController controller) {
        PlaybackState s = controller.getPlaybackState();
        return s == null ? PlaybackState.STATE_NONE : s.getState();
    }

    public enum Action { PREVIOUS, PLAY_PAUSE, NEXT }

    public static void perform(Context context, Action action) {
        MediaController controller = active(context);
        if (controller == null) {
            // No session access: a media key still reaches the last active player without any permission.
            dispatchKey(context, keyCodeFor(action));
            return;
        }
        MediaController.TransportControls t = controller.getTransportControls();
        switch (action) {
            case PREVIOUS: t.skipToPrevious(); break;
            case NEXT: t.skipToNext(); break;
            case PLAY_PAUSE:
                if (isPlaying(controller)) t.pause(); else t.play();
                break;
        }
    }

    private static int keyCodeFor(Action action) {
        switch (action) {
            case PREVIOUS: return KeyEvent.KEYCODE_MEDIA_PREVIOUS;
            case NEXT: return KeyEvent.KEYCODE_MEDIA_NEXT;
            default: return KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE;
        }
    }

    private static void dispatchKey(Context context, int keyCode) {
        AudioManager audio = context.getSystemService(AudioManager.class);
        long now = SystemClock.uptimeMillis();
        audio.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0));
        audio.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0));
    }
}
