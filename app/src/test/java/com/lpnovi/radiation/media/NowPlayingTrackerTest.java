package com.lpnovi.radiation.media;

import static com.lpnovi.radiation.media.NowPlayingTracker.Decision.HOLD;
import static com.lpnovi.radiation.media.NowPlayingTracker.Decision.KEEP_ART;
import static com.lpnovi.radiation.media.NowPlayingTracker.Decision.SHOW;
import static com.lpnovi.radiation.media.NowPlayingTracker.decide;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** decide(sameSession, sameTrack, shownHasArt, newHasArt, heldMs) */
public class NowPlayingTrackerTest {

    @Test
    public void newTrackWithArtworkShowsImmediately() {
        assertEquals(SHOW, decide(true, false, true, true, 0));
    }

    @Test
    public void newTrackBeforeItsArtworkHoldsPreviousTrack() {
        assertEquals(HOLD, decide(true, false, true, false, 0));
        assertEquals(HOLD, decide(true, false, true, false, NowPlayingTracker.HOLD_MS - 1));
    }

    @Test
    public void holdEndsSoMissingArtworkFallsBackEventually() {
        assertEquals(SHOW, decide(true, false, true, false, NowPlayingTracker.HOLD_MS));
    }

    @Test
    public void sameTrackRepublishedWithoutBitmapKeepsArtwork() {
        // The original bug: this case replaced valid artwork with the fallback permanently.
        assertEquals(KEEP_ART, decide(true, true, true, false, 0));
    }

    @Test
    public void switchingPlayersNeverHoldsTheOldOne() {
        assertEquals(SHOW, decide(false, false, true, false, 0));
    }

    @Test
    public void nothingToProtectMeansShow() {
        assertEquals(SHOW, decide(true, false, false, false, 0));
    }

    // --- Bound player without a session ---

    private static NowPlaying shown(String pkg, String title) {
        return new NowPlaying(pkg, title, "Artist", null, null, 0, true, Shuffle.ON, null, true);
    }

    @Test
    public void followActiveWithoutSessionShowsNothing() {
        assertEquals(NowPlaying.NOTHING, NowPlayingTracker.withoutSession(null, shown("a.player", "Song")));
    }

    @Test
    public void boundPlayerKeepsItsLastKnownTrackPausedAndUncontrollable() {
        NowPlaying np = NowPlayingTracker.withoutSession("a.player", shown("a.player", "Song"));
        assertEquals("Song", np.title);
        assertEquals(false, np.playing);
        assertEquals(false, np.hasSession);
        assertEquals(Shuffle.UNSUPPORTED, np.shuffle);
    }

    @Test
    public void boundPlayerNeverShowsAnotherAppsTrack() {
        NowPlaying np = NowPlayingTracker.withoutSession("a.player", shown("other.player", "Not ours"));
        assertEquals("a.player", np.packageName);
        assertEquals(null, np.title);
        assertEquals(false, np.hasSession);
    }
}
