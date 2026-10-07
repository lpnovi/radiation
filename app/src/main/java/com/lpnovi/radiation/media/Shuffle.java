package com.lpnovi.radiation.media;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.service.notification.StatusBarNotification;
import android.support.v4.media.session.MediaControllerCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;

import androidx.annotation.Nullable;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shuffle, across the different ways players expose it.
 *
 * The framework media API has no notion of shuffle, so players use one of several mechanisms.
 * Radiation picks a strategy per session from what that session actually publishes
 * ({@link #choose}, pure and unit-tested), in this order:
 *
 *  1. STANDARD, advertised: the session lists ACTION_SET_SHUFFLE_MODE in its playback actions.
 *     Commanded with the AndroidX compat setShuffleMode (works before the compat handshake too).
 *  2. CUSTOM_ACTION: the session publishes a playback custom action that is clearly shuffle (its id
 *     or name says so, in any of several languages). Sent with sendCustomAction.
 *  3. NOTIFICATION: the player's own media notification has a shuffle button. Its PendingIntent is
 *     sent, exactly what tapping that button in the notification shade does.
 *  4. STANDARD, readable: a compat session that reports a shuffle mode without advertising the
 *     action (how some players, e.g. Spotify, behave).
 *  5. LEGACY: only the deprecated ACTION_SET_SHUFFLE_MODE_ENABLED (old support-library sessions).
 *  6. NONE: nothing safe to send. The button is shown unavailable; other controls are unaffected.
 *
 * A standard command whose effect is readable but doesn't show within CONFIRM_MS counts as ignored
 * (every MediaSessionCompat reports "off" by default, even without shuffle), and the session falls
 * through to the next strategy.
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

    enum Strategy { STANDARD, CUSTOM_ACTION, NOTIFICATION, LEGACY, NONE }

    /** The chosen way to drive one session's shuffle, and what we know of its state. */
    static final class Choice {
        final Strategy strategy;
        final int state;
        /** CUSTOM_ACTION: the action id. NOTIFICATION: the button's title. */
        @Nullable final String target;
        /** NOTIFICATION: index of the button among the notification's actions. */
        final int index;

        Choice(Strategy strategy, int state, @Nullable String target, int index) {
            this.strategy = strategy;
            this.state = state;
            this.target = target;
            this.index = index;
        }
    }

    static final long ACTION_SET_SHUFFLE_MODE = PlaybackStateCompat.ACTION_SET_SHUFFLE_MODE;
    /** Deprecated support-library action (1 << 19); referenced by value to avoid the deprecated API. */
    static final long ACTION_SET_SHUFFLE_MODE_ENABLED = 1L << 19;
    /** The support library's wire format for the deprecated action, as old compat sessions expect it. */
    static final String LEGACY_ACTION = "android.support.v4.media.session.action.SET_SHUFFLE_MODE_ENABLED";
    static final String LEGACY_ARGUMENT = "android.support.v4.media.session.action.ARGUMENT_SHUFFLE_MODE_ENABLED";
    /** How long a player with a readable mode gets to show a change before it counts as ignoring us. */
    public static final long CONFIRM_MS = 2_000;
    static final int COMPAT_INVALID = PlaybackStateCompat.SHUFFLE_MODE_INVALID;

    /**
     * "Shuffle" as players label it. Matched case-insensitively as substrings of a custom action's
     * id/name or a notification button's title. Labels follow the phone's language, hence the list.
     */
    static final String[] SHUFFLE_WORDS = {
            "shuffle", "shufle", "random", "aleat", "aléat", // en, typos/ids, es/pt/fr/ca/ro (aleatorio, aléatoire)
            "zufall", "zufäll", "mischen",          // de
            "casual", "mescola",                    // it
            "willekeurig", "losow", "náhodn",        // nl, pl, cs/sk
            "karıştır", "karistir", "acak",         // tr, id/ms
            "ngẫu nhiên", "xáo trộn", "สุ่ม",       // vi, th
            "перемеш", "случайн", "перемiш", "випадков", // ru, uk
            "عشوائي", "تشغيل عشوائي", "تصادفی", "אקראי", // ar, fa, he
            "शफ़ल", "शफल", "यादृच्छिक",               // hi
            "シャッフル", "随机", "隨機", "셔플", "무작위", // ja, zh, ko
    };

    /** Raw compat shuffle mode per session, present once the compat handshake completed. */
    private static final Map<MediaSession.Token, Integer> compatModes = new ConcurrentHashMap<>();
    /** Last command sent while the state was unknown, to alternate on/off. */
    private static final Map<MediaSession.Token, Boolean> lastSentOn = new ConcurrentHashMap<>();
    /** A standard command awaiting a visible change: {mode before, sent at}. */
    private static final Map<MediaSession.Token, long[]> pending = new ConcurrentHashMap<>();
    /** Sessions that ignored the standard command. */
    private static final Set<MediaSession.Token> unresponsive = Collections.newSetFromMap(new ConcurrentHashMap<>());
    /** Buttons of each session's media notification, from the notification listener. */
    private static final Map<MediaSession.Token, Notification.Action[]> notificationActions = new ConcurrentHashMap<>();

    private Shuffle() {}

    // --- Strategy selection (pure) ---

    static Choice choose(boolean compatReady, int compatMode, long actions, String[] customIds,
                         String[] customNames, String[] notificationTitles, boolean ignoredStandard) {
        boolean advertised = (actions & ACTION_SET_SHUFFLE_MODE) != 0;
        boolean readable = compatReady && fromCompat(compatMode) != UNSUPPORTED;
        int reported = readable ? fromCompat(compatMode) : UNKNOWN; // INVALID: unknown, not unsupported
        if (advertised && !ignoredStandard) return new Choice(Strategy.STANDARD, reported, null, -1);
        String custom = findShuffleAction(customIds, customNames);
        if (custom != null) return new Choice(Strategy.CUSTOM_ACTION, UNKNOWN, custom, -1);
        int button = findShuffle(notificationTitles);
        if (button >= 0) return new Choice(Strategy.NOTIFICATION, UNKNOWN, notificationTitles[button], button);
        if (readable && !ignoredStandard) return new Choice(Strategy.STANDARD, reported, null, -1);
        if ((actions & ACTION_SET_SHUFFLE_MODE_ENABLED) != 0) return new Choice(Strategy.LEGACY, UNKNOWN, null, -1);
        return new Choice(Strategy.NONE, UNSUPPORTED, null, -1);
    }

    /** A custom action that is clearly shuffle by its id or its (user-visible) name; else null. */
    @Nullable
    static String findShuffleAction(String[] ids, String[] names) {
        int i = findShuffle(ids);
        if (i < 0) i = findShuffle(names);
        return i < 0 ? null : ids[i];
    }

    /** Index of the first label that says shuffle, or -1. */
    static int findShuffle(String[] labels) {
        for (int i = 0; i < labels.length; i++) {
            if (labels[i] == null) continue;
            String l = labels[i].toLowerCase(Locale.ROOT);
            for (String w : SHUFFLE_WORDS) if (l.contains(w)) return i;
        }
        return -1;
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
            if (raw == null || raw == wait[0]) { // nothing changed: it ignored us
                unresponsive.add(token);
                Diagnostics.event(controller.getPackageName() + ": no shuffle change within "
                        + CONFIRM_MS + "ms, standard command treated as ignored");
            }
        }
        Snapshot s = new Snapshot(controller);
        Choice c = choose(raw != null, raw == null ? COMPAT_INVALID : raw, s.actions, s.ids, s.names,
                s.titles, unresponsive.contains(token));
        // Record what this session exposes whenever it changes (bounded history, no track info).
        String line = describe(controller, s, raw, c);
        if (!line.equals(logged.put(token, line))) Diagnostics.event(line);
        return c;
    }

    /** What one session exposes that matters for shuffle. */
    private static final class Snapshot {
        final long actions;
        final String[] ids, names, titles;

        Snapshot(MediaController controller) {
            PlaybackState ps = controller.getPlaybackState();
            actions = ps == null ? 0 : ps.getActions();
            List<PlaybackState.CustomAction> customs = ps == null
                    ? Collections.<PlaybackState.CustomAction>emptyList() : ps.getCustomActions();
            ids = new String[customs.size()];
            names = new String[customs.size()];
            for (int i = 0; i < ids.length; i++) {
                ids[i] = customs.get(i).getAction();
                names[i] = String.valueOf(customs.get(i).getName());
            }
            Notification.Action[] buttons = notificationActions.get(controller.getSessionToken());
            titles = new String[buttons == null ? 0 : buttons.length];
            for (int i = 0; i < titles.length; i++) {
                titles[i] = buttons[i].title == null ? null : buttons[i].title.toString();
            }
        }
    }

    /** Sends shuffle through the session's own strategy; does nothing when unsupported. */
    public static void toggle(Context context, MediaController controller) {
        MediaSession.Token token = controller.getSessionToken();
        Choice c = capability(controller);
        String sent;
        switch (c.strategy) {
            case STANDARD: {
                boolean on = nextOn(c.state, lastSentOn.get(token));
                lastSentOn.put(token, on);
                Integer raw = compatModes.get(token);
                if (raw != null) pending.put(token, new long[]{raw, SystemClock.uptimeMillis()});
                compat(context, controller).getTransportControls().setShuffleMode(on
                        ? PlaybackStateCompat.SHUFFLE_MODE_ALL : PlaybackStateCompat.SHUFFLE_MODE_NONE);
                sent = "setShuffleMode(" + (on ? "ALL" : "NONE") + ")";
                break;
            }
            case CUSTOM_ACTION:
                // The player toggles itself; the action is one it published.
                controller.getTransportControls().sendCustomAction(c.target, null);
                sent = "custom action " + c.target;
                break;
            case NOTIFICATION: {
                Notification.Action[] buttons = notificationActions.get(token);
                if (buttons == null || c.index >= buttons.length || buttons[c.index].actionIntent == null) return;
                try {
                    buttons[c.index].actionIntent.send();
                    sent = "notification button \"" + c.target + "\"";
                } catch (PendingIntent.CanceledException e) {
                    sent = "notification button \"" + c.target + "\" (failed: canceled)";
                }
                break;
            }
            case LEGACY: {
                boolean on = nextOn(c.state, lastSentOn.get(token));
                lastSentOn.put(token, on);
                Bundle args = new Bundle();
                args.putBoolean(LEGACY_ARGUMENT, on);
                controller.getTransportControls().sendCustomAction(LEGACY_ACTION, args);
                sent = "legacy shuffle enabled=" + on;
                break;
            }
            default:
                Diagnostics.event(controller.getPackageName() + ": shuffle pressed, but no supported way to send it");
                return;
        }
        Diagnostics.event(controller.getPackageName() + ": shuffle pressed, sent " + sent);
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

    /**
     * Keeps the buttons of players' media notifications (those that name a media session); other
     * notifications are not looked at. Returns true if something was recorded or dropped.
     */
    @SuppressWarnings("deprecation") // typed getParcelable is API 33+
    static boolean onNotification(StatusBarNotification sbn, boolean removed) {
        Notification n = sbn.getNotification();
        if (n == null || n.extras == null) return false;
        Object token = n.extras.getParcelable(Notification.EXTRA_MEDIA_SESSION);
        if (!(token instanceof MediaSession.Token)) return false;
        MediaSession.Token t = (MediaSession.Token) token;
        if (removed || n.actions == null) notificationActions.remove(t);
        else notificationActions.put(t, n.actions);
        return true;
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
        notificationActions.keySet().retainAll(live);
        logged.keySet().retainAll(live);
    }

    // --- Diagnostics (no track metadata) ---

    private static final Map<MediaSession.Token, String> logged = new ConcurrentHashMap<>();

    /** One line describing what a session exposes for shuffle and what Radiation chose. */
    static String describe(MediaController controller) {
        MediaSession.Token token = controller.getSessionToken();
        Integer raw = compatModes.get(token);
        Snapshot s = new Snapshot(controller);
        return describe(controller, s, raw, choose(raw != null, raw == null ? COMPAT_INVALID : raw, s.actions,
                s.ids, s.names, s.titles, unresponsive.contains(token)));
    }

    private static String describe(MediaController controller, Snapshot s, @Nullable Integer raw, Choice c) {
        StringBuilder customs = new StringBuilder();
        for (int i = 0; i < s.ids.length; i++) customs.append(i == 0 ? "" : ", ").append(s.ids[i]).append('=').append(s.names[i]);
        Bundle extras = controller.getExtras();
        return controller.getPackageName()
                + " actions=0x" + Long.toHexString(s.actions)
                + " SET_SHUFFLE_MODE=" + ((s.actions & ACTION_SET_SHUFFLE_MODE) != 0)
                + " SET_SHUFFLE_MODE_ENABLED=" + ((s.actions & ACTION_SET_SHUFFLE_MODE_ENABLED) != 0)
                + " compatReady=" + (raw != null) + " compatMode=" + (raw == null ? "n/a" : raw)
                + " ignored=" + unresponsive.contains(controller.getSessionToken())
                + " custom=[" + customs + "]"
                + " notificationButtons=" + java.util.Arrays.toString(s.titles)
                + " extrasKeys=" + (extras == null ? "[]" : new TreeSet<>(extras.keySet()))
                + " -> strategy=" + c.strategy + " state=" + stateName(c.state)
                + (c.target != null ? " target=" + c.target : "");
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
