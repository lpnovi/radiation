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
}
