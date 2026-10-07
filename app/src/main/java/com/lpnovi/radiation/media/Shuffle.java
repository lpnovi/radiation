package com.lpnovi.radiation.media;

import android.content.Context;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.support.v4.media.session.MediaControllerCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.util.Log;

import androidx.annotation.Nullable;

import com.lpnovi.radiation.BuildConfig;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shuffle, across the different ways players expose it.
 *
 * The framework media API has no notion of shuffle, so players use one of several mechanisms.
 * Radiation picks a strategy per session from what that session actually advertises
 * ({@link #choose}, pure and unit-tested), in this order:
 *
 *  1. STANDARD, advertised: the session lists ACTION_SET_SHUFFLE_MODE in its playback actions.
 *     Commanded with the AndroidX compat setShuffleMode (works before the compat handshake too).
 *  2. CUSTOM_ACTION: the session publishes a playback custom action that is clearly shuffle (its id
 *     or name says so), like the shuffle button in its media notification. Sent with
 *     sendCustomAction; only actions the session itself lists are ever sent.
 *  3. STANDARD, readable: a compat session that reports a shuffle mode without advertising the
 *     action (how some players, e.g. Spotify, behave). If such a player ignores the command, it is
 *     detected (state unchanged after CONFIRM_MS) and the session is treated as unsupported.
 *  4. LEGACY: only the deprecated ACTION_SET_SHUFFLE_MODE_ENABLED (old support-library sessions).
 *  5. NONE: nothing safe to send. The button is shown unavailable; other controls are unaffected.
 *
 * Capability and state are separate: a session can accept the command without reporting its state.
 * Then the state is UNKNOWN (neutral look) rather than a guessed ON/OFF, and becomes known if the
 * session later reports it.
 */
public final class Shuffle {

    // States, as shown by the widget.
    public static final int UNSUPPORTED = -1;
    public static final int OFF = 0;
    public static final int ON = 1;
    /** Shuffle can be commanded, but the player doesn't report whether it's on. */
    public static final int UNKNOWN = 2;

    enum Strategy { STANDARD, CUSTOM_ACTION, LEGACY, NONE }

    /** The chosen way to drive one session's shuffle, and what we know of its state. */
    static final class Choice {
        final Strategy strategy;
        final int state;
        @Nullable final String customAction;
        final boolean advertised;

        Choice(Strategy strategy, int state, @Nullable String customAction, boolean advertised) {
            this.strategy = strategy;
            this.state = state;
            this.customAction = customAction;
            this.advertised = advertised;
        }
    }

    static final long ACTION_SET_SHUFFLE_MODE = PlaybackStateCompat.ACTION_SET_SHUFFLE_MODE;
    /** Deprecated support-library action (1 << 19); referenced by value to avoid the deprecated API. */
    static final long ACTION_SET_SHUFFLE_MODE_ENABLED = 1L << 19;
    /** The support library's wire format for the deprecated action, as old compat sessions expect it. */
    static final String LEGACY_ACTION = "android.support.v4.media.session.action.SET_SHUFFLE_MODE_ENABLED";
    static final String LEGACY_ARGUMENT = "android.support.v4.media.session.action.ARGUMENT_SHUFFLE_MODE_ENABLED";
    /** How long a readable-but-unadvertised player gets to show a change before it counts as ignoring us. */
    public static final long CONFIRM_MS = 2_000;
    static final int COMPAT_INVALID = PlaybackStateCompat.SHUFFLE_MODE_INVALID;

    /** Raw compat shuffle mode per session, present once the compat handshake completed. */
    private static final Map<MediaSession.Token, Integer> compatModes = new ConcurrentHashMap<>();
    /** Last command sent while the state was unknown, to alternate on/off. */
    private static final Map<MediaSession.Token, Boolean> lastSentOn = new ConcurrentHashMap<>();
    /** A command sent on the readable-but-unadvertised path, awaiting a visible change. */
    private static final Map<MediaSession.Token, long[]> pending = new ConcurrentHashMap<>();
    /** Sessions that ignored the standard command (see strategy 3). */
    private static final Set<MediaSession.Token> unresponsive = Collections.newSetFromMap(new ConcurrentHashMap<>());

    private Shuffle() {}

    // --- Strategy selection (pure) ---

    static Choice choose(boolean compatReady, int compatMode, long actions, String[] customIds,
                         String[] customNames, boolean ignoredStandard) {
        boolean advertised = (actions & ACTION_SET_SHUFFLE_MODE) != 0;
        int reported = compatReady ? fromCompat(compatMode) : UNKNOWN;
        if (reported == UNSUPPORTED) reported = UNKNOWN; // INVALID: readable session, unknown state
        if (advertised) return new Choice(Strategy.STANDARD, reported, null, true);
        String custom = findShuffleAction(customIds, customNames);
        if (custom != null) return new Choice(Strategy.CUSTOM_ACTION, UNKNOWN, custom, false);
        if (compatReady && fromCompat(compatMode) != UNSUPPORTED && !ignoredStandard) {
            return new Choice(Strategy.STANDARD, reported, null, false);
        }
        if ((actions & ACTION_SET_SHUFFLE_MODE_ENABLED) != 0) return new Choice(Strategy.LEGACY, UNKNOWN, null, false);
        return new Choice(Strategy.NONE, UNSUPPORTED, null, false);
    }

    /** A custom action that is clearly shuffle by its id or its (user-visible) name; else null. */
    @Nullable
    static String findShuffleAction(String[] ids, String[] names) {
        for (int i = 0; i < ids.length; i++) {
            if (ids[i] != null && ids[i].toLowerCase(Locale.ROOT).contains("shuffle")) return ids[i];
        }
        for (int i = 0; i < names.length; i++) {
            if (names[i] != null && names[i].toLowerCase(Locale.ROOT).contains("shuffle")) return ids[i];
        }
        return null;
    }

    static int fromCompat(int compatMode) {
        switch (compatMode) {
            case PlaybackStateCompat.SHUFFLE_MODE_NONE: return OFF;
            case PlaybackStateCompat.SHUFFLE_MODE_ALL:
            case PlaybackStateCompat.SHUFFLE_MODE_GROUP: return ON;
            default: return UNSUPPORTED;
        }
    }

    /** With an unknown state, alternate commands, starting with "on". */
    static boolean nextOn(int state, @Nullable Boolean lastSent) {
        if (state == ON) return false;
        if (state == OFF) return true;
        return lastSent == null || !lastSent;
    }

    // --- Session side ---

    /** The session's shuffle state as the widget shows it (also re-evaluates ignored commands). */
    public static int of(MediaController controller) {
        return capability(controller).state;
    }

    static Choice capability(MediaController controller) {
        MediaSession.Token token = controller.getSessionToken();
        Integer raw = compatModes.get(token);
        long[] wait = pending.get(token);
        if (wait != null && SystemClock.uptimeMillis() - wait[1] >= CONFIRM_MS) {
            pending.remove(token);
            if (raw == null || raw == wait[0]) unresponsive.add(token); // nothing changed: it ignored us
        }
        PlaybackState ps = controller.getPlaybackState();
        long actions = ps == null ? 0 : ps.getActions();
        List<PlaybackState.CustomAction> customs = ps == null
                ? Collections.<PlaybackState.CustomAction>emptyList() : ps.getCustomActions();
        String[] ids = new String[customs.size()];
        String[] names = new String[customs.size()];
        for (int i = 0; i < ids.length; i++) {
            ids[i] = customs.get(i).getAction();
            names[i] = String.valueOf(customs.get(i).getName());
        }
        Choice c = choose(raw != null, raw == null ? COMPAT_INVALID : raw, actions, ids, names,
                unresponsive.contains(token));
        if (BuildConfig.DEBUG) debugLog(controller, actions, raw, ids, names, c);
        return c;
    }

    /** Sends shuffle through the session's own strategy; does nothing when unsupported. */
    public static void toggle(Context context, MediaController controller) {
        MediaSession.Token token = controller.getSessionToken();
        Choice c = capability(controller);
        switch (c.strategy) {
            case STANDARD: {
                boolean on = nextOn(c.state, lastSentOn.get(token));
                lastSentOn.put(token, on);
                if (!c.advertised) {
                    Integer raw = compatModes.get(token);
                    pending.put(token, new long[]{raw == null ? COMPAT_INVALID : raw, SystemClock.uptimeMillis()});
                }
                compat(context, controller).getTransportControls().setShuffleMode(on
                        ? PlaybackStateCompat.SHUFFLE_MODE_ALL : PlaybackStateCompat.SHUFFLE_MODE_NONE);
                break;
            }
            case CUSTOM_ACTION:
                // The player toggles itself; the action is one it advertised.
                controller.getTransportControls().sendCustomAction(c.customAction, null);
                break;
            case LEGACY: {
                boolean on = nextOn(c.state, lastSentOn.get(token));
                lastSentOn.put(token, on);
                Bundle args = new Bundle();
                args.putBoolean(LEGACY_ARGUMENT, on);
                controller.getTransportControls().sendCustomAction(LEGACY_ACTION, args);
                break;
            }
            default:
                return;
        }
        log(controller.getPackageName() + ": shuffle sent via " + c.strategy
                + (c.customAction != null ? " (" + c.customAction + ")" : ""));
    }

    /** Time until the next ignored-command check is due, or -1 if none is waiting. */
    public static long recheckInMs() {
        long next = -1;
        long now = SystemClock.uptimeMillis();
        for (long[] wait : pending.values()) {
            long left = Math.max(0, wait[1] + CONFIRM_MS - now);
            if (next < 0 || left < next) next = left;
        }
        return next;
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
                    compatModes.put(token, shuffleMode);
                    pending.remove(token);     // it answered
                    unresponsive.remove(token);
                    onChange.run();
                }
            };
            compat.registerCallback(callback, handler);
            if (compat.isSessionReady()) record();
        }

        private void record() {
            compatModes.put(token, compat.getShuffleMode());
        }

        /** Keeps the recorded mode: the same session is usually re-watched straight away. */
        void release() {
            compat.unregisterCallback(callback);
        }
    }

    /** Forgets sessions that no longer exist. */
    static void retainOnly(java.util.Collection<MediaSession.Token> live) {
        compatModes.keySet().retainAll(live);
        lastSentOn.keySet().retainAll(live);
        pending.keySet().retainAll(live);
        unresponsive.retainAll(live);
        logged.keySet().retainAll(live);
    }

    // --- Debug diagnostics (debug builds only; no track metadata is logged) ---

    static final String TAG = "RadiationShuffle";
    private static final Map<MediaSession.Token, String> logged = new ConcurrentHashMap<>();

    private static void debugLog(MediaController controller, long actions, @Nullable Integer raw, String[] ids,
                                 String[] names, Choice c) {
        StringBuilder customs = new StringBuilder();
        for (int i = 0; i < ids.length; i++) customs.append(i == 0 ? "" : ", ").append(ids[i]).append('=').append(names[i]);
        Bundle extras = controller.getExtras();
        String line = controller.getPackageName()
                + " actions=0x" + Long.toHexString(actions)
                + " SET_SHUFFLE_MODE=" + ((actions & ACTION_SET_SHUFFLE_MODE) != 0)
                + " SET_SHUFFLE_MODE_ENABLED=" + ((actions & ACTION_SET_SHUFFLE_MODE_ENABLED) != 0)
                + " compatReady=" + (raw != null) + " compatMode=" + (raw == null ? "n/a" : raw)
                + " custom=[" + customs + "]"
                + " extrasKeys=" + (extras == null ? "[]" : new java.util.TreeSet<>(extras.keySet()))
                + " -> strategy=" + c.strategy + " state=" + stateName(c.state)
                + (c.customAction != null ? " action=" + c.customAction : "");
        // Only when something changed, so the log stays readable.
        if (!line.equals(logged.put(controller.getSessionToken(), line))) Log.d(TAG, line);
    }

    private static void log(String message) {
        if (BuildConfig.DEBUG) Log.d(TAG, message);
    }

    static String stateName(int state) {
        switch (state) {
            case ON: return "ON";
            case OFF: return "OFF";
            case UNKNOWN: return "UNKNOWN_SUPPORTED";
            default: return "UNSUPPORTED";
        }
    }
}
