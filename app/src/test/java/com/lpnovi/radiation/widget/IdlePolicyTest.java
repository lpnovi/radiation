package com.lpnovi.radiation.widget;

import static com.lpnovi.radiation.widget.IdlePolicy.NEVER;
import static com.lpnovi.radiation.widget.IdlePolicy.STOP_GRACE_MS;
import static com.lpnovi.radiation.widget.IdlePolicy.UNKNOWN;
import static com.lpnovi.radiation.widget.IdlePolicy.isIdle;
import static com.lpnovi.radiation.widget.IdlePolicy.untilIdle;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.lpnovi.radiation.config.WidgetConfig;

import org.junit.Test;

public class IdlePolicyTest {

    private static final long MIN = 60_000, T = 10 * MIN;

    @Test
    public void playingIsNeverIdle() {
        assertFalse(isIdle(true, false, UNKNOWN, T, MIN));
        assertFalse(isIdle(true, false, T - 100 * MIN, T, MIN));
    }

    @Test
    public void nothingEverPlayedIsIdleRightAway() {
        assertTrue(isIdle(false, false, UNKNOWN, T, MIN));
    }

    /** Track changes and player restarts briefly drop the session: no bounce into idle. */
    @Test
    public void sessionGapsShorterThanTheGraceStayOnMedia() {
        assertFalse(isIdle(false, false, T - 1_000, T, MIN));
        assertFalse(isIdle(false, false, T - (STOP_GRACE_MS - 1), T, MIN));
        assertTrue(isIdle(false, false, T - STOP_GRACE_MS, T, MIN));
    }

    @Test
    public void shortPauseKeepsTheSongOnScreen() {
        assertFalse(isIdle(false, true, T - 30_000, T, MIN));
        assertTrue(isIdle(false, true, T - MIN, T, MIN));
    }

    @Test
    public void pauseDelayNeverMeansOnlyStoppingGoesIdle() {
        assertFalse(isIdle(false, true, T - 100 * MIN, T, NEVER));
        assertTrue(isIdle(false, false, T - 100 * MIN, T, NEVER));
    }

    @Test
    public void schedulesExactlyOneRecheckAtTheBoundary() {
        assertEquals(30_000, untilIdle(false, true, T - 30_000, T, MIN));
        assertEquals(STOP_GRACE_MS - 1_000, untilIdle(false, false, T - 1_000, T, MIN));
        assertEquals(NEVER, untilIdle(true, false, T, T, MIN));            // playing: nothing to wait for
        assertEquals(NEVER, untilIdle(false, true, T - 2 * MIN, T, MIN));  // already idle
        assertEquals(NEVER, untilIdle(false, true, T - 1_000, T, NEVER));  // paused, never idles
    }

    @Test
    public void pauseDelaysMatchTheSetting() {
        assertEquals(MIN, IdlePolicy.pauseDelayMs(WidgetConfig.PauseIdle.AFTER_1_MIN));
        assertEquals(5 * MIN, IdlePolicy.pauseDelayMs(WidgetConfig.PauseIdle.AFTER_5_MIN));
        assertEquals(NEVER, IdlePolicy.pauseDelayMs(WidgetConfig.PauseIdle.NEVER));
    }
}
