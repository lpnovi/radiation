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
        return Shuffle.choose(ready, mode, actions, NO, NO, false);
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
    public void readableButUnadvertisedKeepsWorkingUntilIgnored() {
        Shuffle.Choice c = choose(true, NONE, 0);
        assertEquals(Shuffle.Strategy.STANDARD, c.strategy);
        assertEquals(Shuffle.OFF, c.state);
        assertFalse(c.advertised);
        Shuffle.Choice ignored = Shuffle.choose(true, NONE, 0, NO, NO, true);
        assertEquals(Shuffle.Strategy.NONE, ignored.strategy);
        assertEquals(Shuffle.UNSUPPORTED, ignored.state);
    }

    @Test
    public void advertisedNeverDegradesEvenIfIgnoredOnce() {
        assertEquals(Shuffle.Strategy.STANDARD, Shuffle.choose(true, NONE, ADVERTISED, NO, NO, true).strategy);
    }

    @Test
    public void customActionBeatsUnadvertisedDefaultMode() {
        // A compat session reports NONE by default even if it never implements shuffle; its own
        // custom action is the real control.
        Shuffle.Choice c = Shuffle.choose(true, NONE, 0, new String[]{"like", "player.TOGGLE_SHUFFLE"},
                new String[]{"Like", "Shuffle off"}, false);
        assertEquals(Shuffle.Strategy.CUSTOM_ACTION, c.strategy);
        assertEquals("player.TOGGLE_SHUFFLE", c.customAction);
        assertEquals(Shuffle.UNKNOWN, c.state);
    }

    @Test
    public void advertisedStandardBeatsCustomAction() {
        Shuffle.Choice c = Shuffle.choose(true, ALL, ADVERTISED, new String[]{"shuffle"}, new String[]{"Shuffle"}, false);
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
