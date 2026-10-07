package com.lpnovi.radiation.media;

import android.app.PendingIntent;
import android.graphics.Bitmap;

import androidx.annotation.Nullable;

import com.lpnovi.radiation.color.ColorEngine;

/** Immutable snapshot of what a widget should show. Produced by {@link NowPlayingTracker}. */
public final class NowPlaying {

    public static final NowPlaying NOTHING = new NowPlaying(null, null, null, null, null,
            ColorEngine.NONE, false, Shuffle.UNSUPPORTED, null, false);

    /** Package of the session shown (or of the bound player while it has none); null when nothing. */
    @Nullable public final String packageName;
    @Nullable public final CharSequence title;
    @Nullable public final CharSequence artist;
    /** Square, downsampled artwork; null when the track truly has none. */
    @Nullable public final Bitmap art;
    /** Identifies {@link #art} for render-side caches. */
    @Nullable public final String artKey;
    /** Album-art seed color for the color engine, or {@link ColorEngine#NONE}. */
    public final int seed;
    public final boolean playing;
    /** {@link Shuffle#ON}, {@link Shuffle#OFF} or {@link Shuffle#UNSUPPORTED}. */
    public final int shuffle;
    @Nullable public final PendingIntent sessionActivity;
    /**
     * False when nothing can be controlled right now: no session, or (for a bound widget) the
     * last-known track of a player whose session has gone. Controls then open the player instead.
     */
    public final boolean hasSession;

    NowPlaying(@Nullable String packageName, @Nullable CharSequence title, @Nullable CharSequence artist,
               @Nullable Bitmap art, @Nullable String artKey, int seed, boolean playing, int shuffle,
               @Nullable PendingIntent sessionActivity, boolean hasSession) {
        this.packageName = packageName;
        this.title = title;
        this.artist = artist;
        this.art = art;
        this.artKey = artKey;
        this.seed = seed;
        this.playing = playing;
        this.shuffle = shuffle;
        this.sessionActivity = sessionActivity;
        this.hasSession = hasSession;
    }

    /** A bound player with no session and nothing remembered: the widget names the player instead. */
    static NowPlaying idle(String packageName) {
        return new NowPlaying(packageName, null, null, null, null, ColorEngine.NONE, false,
                Shuffle.UNSUPPORTED, null, false);
    }

    /** Studio preview content when nothing real is available, e.g. to judge long titles. */
    public static NowPlaying sample(CharSequence title, CharSequence artist, @Nullable Bitmap art, int seed) {
        return new NowPlaying("sample", title, artist, art, "sample", seed, true, Shuffle.OFF, null, true);
    }

    NowPlaying withArtOf(NowPlaying other) {
        return new NowPlaying(packageName, title, artist, other.art, other.artKey, other.seed, playing, shuffle,
                sessionActivity, hasSession);
    }

    /** For optimistic rendering of a tap. */
    public NowPlaying withPlaying(boolean playing) {
        return new NowPlaying(packageName, title, artist, art, artKey, seed, playing, shuffle, sessionActivity,
                hasSession);
    }

    /** For optimistic rendering of a tap. */
    public NowPlaying withShuffle(int shuffle) {
        return new NowPlaying(packageName, title, artist, art, artKey, seed, playing, shuffle, sessionActivity,
                hasSession);
    }

    /** Last-known track of a bound player whose session went away: shown paused, not controllable. */
    NowPlaying asLastKnown() {
        return new NowPlaying(packageName, title, artist, art, artKey, seed, false, Shuffle.UNSUPPORTED,
                sessionActivity, false);
    }

    /** Previous track's visuals with live playback state/intent: used while waiting for new artwork. */
    NowPlaying heldWithStateOf(NowPlaying live) {
        return new NowPlaying(packageName, title, artist, art, artKey, seed, live.playing, live.shuffle,
                live.sessionActivity, live.hasSession);
    }
}
