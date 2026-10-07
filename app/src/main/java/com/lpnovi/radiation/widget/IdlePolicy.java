package com.lpnovi.radiation.widget;

import com.lpnovi.radiation.config.WidgetConfig;

/**
 * When a widget with Idle mode on switches between its media UI and its idle content.
 * Pure (no Android types), unit-tested.
 *
 *  - Playing (including buffering/skipping): media, immediately. A resume always wins at once.
 *  - Session gone, stopped or errored: idle after STOP_GRACE_MS. The grace absorbs track changes
 *    and players that briefly drop their session while restarting, so the widget doesn't bounce.
 *  - Paused: idle after the widget's pause delay (1 or 5 minutes, or never), so a short pause
 *    keeps the song on screen.
 *
 * "Since playing" is measured from the last moment this widget's player was seen playing; a
 * player never seen playing (e.g. Radiation just started next to a paused app) counts as long ago.
 */
final class IdlePolicy {

    static final long STOP_GRACE_MS = 4_000;
    static final long NEVER = -1;
    /** lastPlayingAt value meaning "not seen playing since Radiation started". */
    static final long UNKNOWN = 0;

    private IdlePolicy() {}

    static long pauseDelayMs(WidgetConfig.PauseIdle p) {
        switch (p) {
            case AFTER_1_MIN: return 60_000;
            case AFTER_5_MIN: return 5 * 60_000;
            default: return NEVER;
        }
    }

    /** Delay before a non-playing player counts as idle, or NEVER. */
    static long delayMs(boolean paused, long pauseDelayMs) {
        return paused ? pauseDelayMs : STOP_GRACE_MS;
    }

    static boolean isIdle(boolean playing, boolean paused, long lastPlayingAt, long now, long pauseDelayMs) {
        if (playing) return false;
        long delay = delayMs(paused, pauseDelayMs);
        if (delay == NEVER) return false;
        return lastPlayingAt == UNKNOWN || now - lastPlayingAt >= delay;
    }

    /** Milliseconds until this widget would turn idle without any other event, or NEVER. */
    static long untilIdle(boolean playing, boolean paused, long lastPlayingAt, long now, long pauseDelayMs) {
        if (playing || lastPlayingAt == UNKNOWN) return NEVER;
        long delay = delayMs(paused, pauseDelayMs);
        if (delay == NEVER) return NEVER;
        long left = lastPlayingAt + delay - now;
        return left > 0 ? left : NEVER;
    }
}
