package com.lpnovi.radiation.media;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.support.v4.media.session.PlaybackStateCompat;

import org.junit.Test;

public class ShuffleTest {

    private static final long ADVERTISED = Shuffle.ACTION_SET_SHUFFLE_MODE;
    private static final int NONE = PlaybackStateCompat.SHUFFLE_MODE_NONE, ALL = PlaybackStateCompat.SHUFFLE_MODE_ALL;
    private static final String[] NO = new String[0];

    private static Shuffle.Choice choose(boolean ready, int mode, long actions) {
        return Shuffle.choose(ready, mode, actions, NO, NO, NO, false);
    }

    @Test
    public void spotifyStyleCompatReportsItsState() {
        Shuffle.Choice c = choose(true, ALL, ADVERTISED);
        assertEquals(Shuffle.Strategy.STANDARD, c.strategy);
        assertEquals(Shuffle.ON, c.state);
        assertEquals(Shuffle.OFF, choose(true, NONE, ADVERTISED).state);
    }

    @Test
    public void advertisedButStateUnreadableIsSupportedUnknown() {
        // Handshake not done yet, or a framework session that only advertises the action.
        Shuffle.Choice c = choose(false, Shuffle.COMPAT_INVALID, ADVERTISED);
        assertEquals(Shuffle.Strategy.STANDARD, c.strategy);
        assertEquals(Shuffle.UNKNOWN, c.state);
        // getShuffleMode() == INVALID is "unknown", not "unsupported".
        assertEquals(Shuffle.UNKNOWN, choose(true, Shuffle.COMPAT_INVALID, ADVERTISED).state);
    }

    @Test
    public void notificationShuffleButton() {
        // A compat session reporting the default NONE, whose only real shuffle is its notification button.
        Shuffle.Choice c = Shuffle.choose(true, NONE, 0, NO, NO, new String[]{"Previous", "Pause", "Next", "Shuffle"}, false);
        assertEquals(Shuffle.Strategy.NOTIFICATION, c.strategy);
        assertEquals(3, c.index);
        assertEquals(Shuffle.UNKNOWN, c.state);
        // Session custom action is preferred: it is addressed to the session itself.
        assertEquals(Shuffle.Strategy.CUSTOM_ACTION, Shuffle.choose(true, NONE, 0, new String[]{"shuffle"},
                new String[]{"Shuffle"}, new String[]{"Shuffle"}, false).strategy);
    }

    @Test
    public void ignoredAdvertisedFallsThroughToOtherRoutes() {
        Shuffle.Choice c = Shuffle.choose(true, NONE, ADVERTISED, NO, NO, new String[]{"Aleatorio"}, true);
        assertEquals(Shuffle.Strategy.NOTIFICATION, c.strategy);
        assertEquals(Shuffle.Strategy.NONE, Shuffle.choose(true, NONE, ADVERTISED, NO, NO, NO, true).strategy);
    }

    @Test
    public void localizedLabels() {
        for (String label : new String[]{"Lecture aléatoire", "Zufallswiedergabe", "Reprodução aleatória",
                "Putar acak", "Перемешать", "シャッフル再生", "随机播放", "셔플", "शफ़ल करें", "ACTION_SHUFFLE_MODE"}) {
            assertEquals(label, 0, Shuffle.findShuffle(new String[]{label}));
        }
        assertEquals(-1, Shuffle.findShuffle(new String[]{"Repeat", "Like", "Close", "Favorite", "Play mode"}));
    }

    @Test
    public void readableButUnadvertisedKeepsWorkingUntilIgnored() {
        Shuffle.Choice c = choose(true, NONE, 0);
        assertEquals(Shuffle.Strategy.STANDARD, c.strategy);
        assertEquals(Shuffle.OFF, c.state);
        Shuffle.Choice ignored = Shuffle.choose(true, NONE, 0, NO, NO, NO, true);
        assertEquals(Shuffle.Strategy.NONE, ignored.strategy);
        assertEquals(Shuffle.UNSUPPORTED, ignored.state);
    }

    @Test
    public void advertisedAndAnsweringStaysStandard() {
        assertEquals(Shuffle.Strategy.STANDARD, Shuffle.choose(true, NONE, ADVERTISED, NO, NO, NO, false).strategy);
    }

    @Test
    public void customActionBeatsUnadvertisedDefaultMode() {
        // A compat session reports NONE by default even if it never implements shuffle; its own
        // custom action is the real control.
        Shuffle.Choice c = Shuffle.choose(true, NONE, 0, new String[]{"like", "player.TOGGLE_SHUFFLE"},
                new String[]{"Like", "Shuffle off"}, NO, false);
        assertEquals(Shuffle.Strategy.CUSTOM_ACTION, c.strategy);
        assertEquals("player.TOGGLE_SHUFFLE", c.target);
        assertEquals(Shuffle.UNKNOWN, c.state);
    }

    @Test
    public void advertisedStandardBeatsCustomAction() {
        Shuffle.Choice c = Shuffle.choose(true, ALL, ADVERTISED, new String[]{"shuffle"}, new String[]{"Shuffle"}, NO, false);
        assertEquals(Shuffle.Strategy.STANDARD, c.strategy);
        assertEquals(Shuffle.ON, c.state);
    }

    @Test
    public void customActionMatching() {
        assertEquals("ACTION_SHUFFLE", Shuffle.findShuffleAction(new String[]{"a", "ACTION_SHUFFLE"}, new String[]{"A", "B"}));
        // By name when the id is opaque.
        assertEquals("cmd_7", Shuffle.findShuffleAction(new String[]{"cmd_3", "cmd_7"}, new String[]{"Repeat", "Shuffle"}));
        // Id match preferred over a name match.
        assertEquals("x.shuffle", Shuffle.findShuffleAction(new String[]{"cmd_1", "x.shuffle"}, new String[]{"Shuffle", "?"}));
        assertNull(Shuffle.findShuffleAction(new String[]{"repeat", "like"}, new String[]{"Repeat", "Like"}));
    }

    @Test
    public void legacyOnlyAndNothing() {
        assertEquals(Shuffle.Strategy.LEGACY, choose(false, Shuffle.COMPAT_INVALID, Shuffle.ACTION_SET_SHUFFLE_MODE_ENABLED).strategy);
        Shuffle.Choice none = choose(false, Shuffle.COMPAT_INVALID, PlaybackStateCompat.ACTION_PLAY_PAUSE);
        assertEquals(Shuffle.Strategy.NONE, none.strategy);
        assertEquals(Shuffle.UNSUPPORTED, none.state);
    }

    @Test
    public void unknownAlternatesStartingWithOn() {
        assertTrue(Shuffle.nextOn(Shuffle.UNKNOWN, null));
        assertFalse(Shuffle.nextOn(Shuffle.UNKNOWN, true));
        assertTrue(Shuffle.nextOn(Shuffle.UNKNOWN, false));
        assertFalse(Shuffle.nextOn(Shuffle.ON, false));
        assertTrue(Shuffle.nextOn(Shuffle.OFF, true));
    }

    // --- Runtime visibility (the saved "Show shuffle" setting is an input, never changed) ---

    private static final String LARK = "com.dywx.larkplayer", SPOTIFY = "com.spotify.music";
    /** Lark as its diagnostic report showed it: readable default mode, no shuffle action, LIKE/Stop only. */
    private static final String[] LARK_IDS = {"com.dywx.larkplayer.remote.LIKE", "com.dywx.larkplayer.remote.Stop"};
    private static final String[] LARK_NAMES = {"Like", "Stop"};

    private static int larkState(boolean ignoredOnce) {
        return Shuffle.choose(true, NONE, 0x236, LARK_IDS, LARK_NAMES, NO, ignoredOnce).state;
    }

    private static int spotifyState() {
        return choose(true, ALL, ADVERTISED).state;
    }

    @Test
    public void a_supportedPlayerShowsShuffle() {
        assertEquals(Shuffle.ON, spotifyState());
        assertTrue(Shuffle.visible(true, true, spotifyState(), false));
    }

    @Test
    public void b_larkHidesShuffleOnceItsIgnoredCommandIsSeen() {
        assertEquals(Shuffle.UNSUPPORTED, larkState(true));
        assertFalse(Shuffle.visible(true, true, larkState(true), false));
        // Remembered: no flash of a dead button the next time Lark has no session or restarts.
        assertFalse(Shuffle.visible(true, false, Shuffle.UNSUPPORTED, true));
    }

    @Test
    public void c_settingOffHidesEvenForSupportedPlayers() {
        assertFalse(Shuffle.visible(false, true, spotifyState(), false));
        assertFalse(Shuffle.visible(false, true, Shuffle.UNKNOWN, false));
    }

    @Test
    public void d_e_switchingPlayersFlipsVisibilityWithoutTouchingTheSetting() {
        boolean setting = true;
        assertFalse(Shuffle.visible(setting, true, larkState(true), false));   // Lark
        assertTrue(Shuffle.visible(setting, true, spotifyState(), false));     // then Spotify: back
        assertFalse(Shuffle.visible(setting, true, larkState(true), false));   // then Lark again: gone
        assertTrue(setting);
    }

    @Test
    public void f_boundLarkKeepsItsControlsAndNeverBorrowsShuffle() {
        // Bound to Lark while Spotify plays: controls still resolve to Lark's session...
        assertEquals(1, MediaSessions.pick(new String[]{SPOTIFY, LARK},
                new int[]{android.media.session.PlaybackState.STATE_PLAYING,
                        android.media.session.PlaybackState.STATE_PAUSED}, LARK));
        // ...and Lark has no shuffle to send, so nothing is sent anywhere.
        assertEquals(Shuffle.Strategy.NONE, Shuffle.choose(true, NONE, 0x236, LARK_IDS, LARK_NAMES, NO, true).strategy);
    }

    @Test
    public void g_unknownSupportedStaysAvailable() {
        assertTrue(Shuffle.visible(true, true, Shuffle.UNKNOWN, false));
        assertFalse(Shuffle.UNKNOWN == Shuffle.UNSUPPORTED);
    }

    @Test
    public void noSessionKeepsTheButtonUnlessThePlayerIsKnownToLackShuffle() {
        assertTrue(Shuffle.visible(true, false, Shuffle.UNSUPPORTED, false));
    }
}
