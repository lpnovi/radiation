package com.lpnovi.radiation.media;

import static org.junit.Assert.assertEquals;

import android.media.session.PlaybackState;

import org.junit.Test;

public class MediaSessionsTest {

    @Test
    public void picksFirstPlayingSession() {
        int[] states = {PlaybackState.STATE_PAUSED, PlaybackState.STATE_PLAYING, PlaybackState.STATE_PLAYING};
        assertEquals(1, MediaSessions.pick(states));
    }

    @Test
    public void fallsBackToTopSessionWhenNothingPlays() {
        assertEquals(0, MediaSessions.pick(new int[]{PlaybackState.STATE_PAUSED, PlaybackState.STATE_STOPPED}));
    }

    @Test
    public void noSessions() {
        assertEquals(-1, MediaSessions.pick(new int[0]));
    }
}
