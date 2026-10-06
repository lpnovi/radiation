package com.lpnovi.radiation.media;

import android.app.PendingIntent;
import android.graphics.Bitmap;

import androidx.annotation.Nullable;

import com.lpnovi.radiation.color.ColorEngine;

/** Immutable snapshot of what every widget should show. Produced by {@link NowPlayingTracker}. */
public final class NowPlaying {

    public static final NowPlaying NOTHING = new NowPlaying(null, null, null, null, null,
            ColorEngine.NONE, false, null);

    /** Package of the session shown, or null when nothing is available. */
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
    @Nullable public final PendingIntent sessionActivity;

    NowPlaying(@Nullable String packageName, @Nullable CharSequence title, @Nullable CharSequence artist,
               @Nullable Bitmap art, @Nullable String artKey, int seed, boolean playing,
               @Nullable PendingIntent sessionActivity) {
        this.packageName = packageName;
        this.title = title;
        this.artist = artist;
        this.art = art;
        this.artKey = artKey;
        this.seed = seed;
        this.playing = playing;
        this.sessionActivity = sessionActivity;
    }

    NowPlaying withArtOf(NowPlaying other) {
        return new NowPlaying(packageName, title, artist, other.art, other.artKey, other.seed, playing, sessionActivity);
    }

    /** Previous track's visuals with live playback state/intent: used while waiting for new artwork. */
    NowPlaying heldWithStateOf(NowPlaying live) {
        return new NowPlaying(packageName, title, artist, art, artKey, seed, live.playing, live.sessionActivity);
    }
}
