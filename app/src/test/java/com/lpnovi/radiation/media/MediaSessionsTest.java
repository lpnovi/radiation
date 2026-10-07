package com.lpnovi.radiation.media;

import static org.junit.Assert.assertEquals;

import android.media.session.PlaybackState;

import org.junit.Test;

public class MediaSessionsTest {

    private static final int PLAYING = PlaybackState.STATE_PLAYING, PAUSED = PlaybackState.STATE_PAUSED;
    private static final String SPOTIFY = "com.spotify.music", YTM = "com.google.android.apps.youtube.music";

    @Test
    public void followActivePicksFirstPlayingSession() {
        assertEquals(1, MediaSessions.pick(new String[]{SPOTIFY, YTM, SPOTIFY}, new int[]{PAUSED, PLAYING, PLAYING}, null));
    }

    @Test
    public void followActiveFallsBackToTopSessionWhenNothingPlays() {
        assertEquals(0, MediaSessions.pick(new String[]{SPOTIFY, YTM},
                new int[]{PAUSED, PlaybackState.STATE_STOPPED}, null));
    }

    @Test
    public void noSessions() {
        assertEquals(-1, MediaSessions.pick(new String[0], new int[0], null));
        assertEquals(-1, MediaSessions.pick(new String[0], new int[0], SPOTIFY));
    }

    /** Another app starting playback must not take over a bound widget. */
    @Test
    public void boundWidgetIgnoresOtherPlayers() {
        assertEquals(1, MediaSessions.pick(new String[]{YTM, SPOTIFY}, new int[]{PLAYING, PAUSED}, SPOTIFY));
    }

    @Test
    public void boundPlayerWithoutSessionGivesNothingRatherThanAnotherApp() {
        assertEquals(-1, MediaSessions.pick(new String[]{YTM}, new int[]{PLAYING}, SPOTIFY));
    }

    @Test
    public void boundPlayerWithSeveralSessionsPrefersThePlayingOne() {
        assertEquals(2, MediaSessions.pick(new String[]{SPOTIFY, YTM, SPOTIFY},
                new int[]{PAUSED, PLAYING, PLAYING}, SPOTIFY));
    }
}
