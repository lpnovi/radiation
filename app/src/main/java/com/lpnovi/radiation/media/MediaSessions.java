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

    /** The session to show for a follow-active widget, or null. */
    @Nullable
    public static MediaController active(Context context) {
        return active(context, null);
    }

    /**
     * The session a widget should reflect, or null when nothing is available or access is not
     * granted. With a bound package, only that app's sessions are considered: another player
     * starting playback never takes over a bound widget.
     */
    @Nullable
    public static MediaController active(Context context, @Nullable String boundPackage) {
        List<MediaController> sessions;
        try {
            sessions = context.getSystemService(MediaSessionManager.class)
                    .getActiveSessions(new ComponentName(context, MediaListenerService.class));
        } catch (SecurityException e) {
            return null;
        }
        String[] packages = new String[sessions.size()];
        int[] states = new int[sessions.size()];
        for (int i = 0; i < states.length; i++) {
            packages[i] = sessions.get(i).getPackageName();
            states[i] = stateOf(sessions.get(i));
        }
        int i = pick(packages, states, boundPackage);
        return i < 0 ? null : sessions.get(i);
    }

    /**
     * Sessions arrive in the system's priority order (most recently active first). Among the
     * candidates (all sessions, or only the bound package's), prefer the first one that is actually
     * playing, otherwise the top one, so a paused Spotify still shows. -1 when there is no candidate.
     */
    static int pick(String[] packages, int[] states, @Nullable String boundPackage) {
        int first = -1;
        for (int i = 0; i < states.length; i++) {
            if (boundPackage != null && !boundPackage.equals(packages[i])) continue;
            if (isActiveState(states[i])) return i;
            if (first < 0) first = i;
        }
        return first;
    }

    public static boolean isPlaying(@Nullable MediaController controller) {
        return controller != null && isActiveState(stateOf(controller));
    }

    /**
     * Playing, or about to be (buffering, skipping, seeking). Treating transitional states as playing
     * keeps the pause icon steady through a track change instead of flickering to play.
     */
    static boolean isActiveState(int state) {
        switch (state) {
            case PlaybackState.STATE_PLAYING:
            case PlaybackState.STATE_BUFFERING:
            case PlaybackState.STATE_CONNECTING:
            case PlaybackState.STATE_SKIPPING_TO_NEXT:
            case PlaybackState.STATE_SKIPPING_TO_PREVIOUS:
            case PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM:
            case PlaybackState.STATE_FAST_FORWARDING:
            case PlaybackState.STATE_REWINDING:
                return true;
            default:
                return false;
        }
    }

    private static int stateOf(MediaController controller) {
        PlaybackState s = controller.getPlaybackState();
        return s == null ? PlaybackState.STATE_NONE : s.getState();
    }

    public enum Action { PREVIOUS, PLAY_PAUSE, NEXT, SHUFFLE }

    /**
     * Sends an action to the widget's player: the bound app's session, or the active one.
     * Without a session, a follow-active widget falls back to a media key; a bound widget does not,
     * because the system would deliver the key to whichever app it chooses. (For a bound player with
     * no session, the renderer turns play into "open the player" instead.)
     */
    public static void perform(Context context, Action action, @Nullable String boundPackage) {
        MediaController controller = active(context, boundPackage);
        if (controller == null) {
            if (boundPackage != null) return;
            // No session access: a media key still reaches the last active player without any permission.
            // There is no media key for shuffle, so that one needs access.
            if (action == Action.SHUFFLE) return;
            dispatchKey(context, keyCodeFor(action));
            return;
        }
        MediaController.TransportControls t = controller.getTransportControls();
        switch (action) {
            case PREVIOUS: t.skipToPrevious(); break;
            case NEXT: t.skipToNext(); break;
            case SHUFFLE: Shuffle.toggle(context, controller); break;
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
