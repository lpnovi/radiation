package com.lpnovi.radiation.media;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.net.Uri;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;
import android.util.LruCache;

import androidx.annotation.Nullable;
import androidx.palette.graphics.Palette;

import com.lpnovi.radiation.BuildConfig;
import com.lpnovi.radiation.color.ColorEngine;

import java.io.InputStream;
import java.util.List;
import java.util.Objects;

/**
 * Turns the active media session into a {@link NowPlaying}, smoothing over how players publish
 * metadata. Spotify typically publishes a new track's text first and its artwork in a later
 * metadata update, and sometimes republishes the same track without a bitmap. Rules:
 *
 *  - same track, artwork missing  -> keep the artwork we already have for it
 *  - new track, artwork missing   -> keep showing the previous track for up to HOLD_MS, so text,
 *                                    art and colors switch together instead of flashing a fallback
 *  - still missing after the hold -> show the new track with the fallback tile, and look once more
 *                                    after RETRY_MS in case the artwork never triggered a callback
 *
 * Only ever called on the render thread.
 */
public final class NowPlayingTracker {

    static final String TAG = "Radiation";
    static final long HOLD_MS = 1200;
    static final long RETRY_MS = 3000;
    /** Stored artwork edge. ~72dp at xxhdpi; keeps RemoteViews bitmaps far below Binder limits. */
    static final int ART_PX = 256;

    public static final class Result {
        public final NowPlaying nowPlaying;
        /** > 0: resolve again after this many ms. */
        public final long recheckInMs;

        Result(NowPlaying nowPlaying, long recheckInMs) {
            this.nowPlaying = nowPlaying;
            this.recheckInMs = recheckInMs;
        }
    }

    enum Decision { SHOW, KEEP_ART, HOLD }

    /** The whole transition policy, kept pure for tests. */
    static Decision decide(boolean sameSession, boolean sameTrack, boolean shownHasArt,
                           boolean newHasArt, long heldMs) {
        if (newHasArt || !shownHasArt || !sameSession) return Decision.SHOW;
        if (sameTrack) return Decision.KEEP_ART;
        return heldMs < HOLD_MS ? Decision.HOLD : Decision.SHOW;
    }

    private static final class Processed {
        final Bitmap art;
        final int seed;

        Processed(Bitmap art, int seed) {
            this.art = art;
            this.seed = seed;
        }
    }

    /** Palette work per artwork happens once; skipping back to a recent track is free. */
    private final LruCache<String, Processed> processed = new LruCache<>(8);
    private NowPlaying shown = NowPlaying.NOTHING;
    private String shownTrack;
    private long holdStartedAt;
    private String retriedTrack;

    /** Resolves what widgets with this binding (null = follow active) should show. */
    public Result resolve(Context context, @Nullable String boundPackage) {
        MediaController controller = MediaSessions.active(context, boundPackage);
        if (controller == null) {
            NowPlaying idle = withoutSession(boundPackage, shown);
            if (boundPackage == null) reset();
            return new Result(idle, 0);
        }
        MediaMetadata meta = controller.getMetadata();
        String pkg = controller.getPackageName();
        CharSequence title = meta == null ? null : meta.getText(MediaMetadata.METADATA_KEY_TITLE);
        CharSequence artist = meta == null ? null : firstNonEmpty(
                meta.getText(MediaMetadata.METADATA_KEY_ARTIST),
                meta.getText(MediaMetadata.METADATA_KEY_ALBUM_ARTIST));
        String track = pkg + '\u0000' + title + '\u0000' + artist + '\u0000'
                + (meta == null ? null : meta.getString(MediaMetadata.METADATA_KEY_ALBUM));

        NowPlaying live = new NowPlaying(pkg, title, artist, null, null, ColorEngine.NONE,
                MediaSessions.isPlaying(controller), Shuffle.of(controller), controller.getSessionActivity(), true,
                MediaSessions.isPaused(controller));
        Bitmap source = meta == null ? null : readArtwork(context, meta);
        if (source != null) {
            String artKey = track + '\u0000' + fingerprint(source);
            Processed p = processed.get(artKey);
            if (p == null) {
                p = process(source);
                processed.put(artKey, p);
                log("artwork processed " + source.getWidth() + "x" + source.getHeight() + " seed=#"
                        + Integer.toHexString(p.seed) + " for " + title);
            }
            live = new NowPlaying(pkg, title, artist, p.art, artKey, p.seed, live.playing, live.shuffle,
                    live.sessionActivity, true, live.paused);
        }

        long now = SystemClock.uptimeMillis();
        boolean sameTrack = track.equals(shownTrack);
        if (sameTrack || source != null) holdStartedAt = 0;
        else if (holdStartedAt == 0) holdStartedAt = now;
        Decision d = decide(Objects.equals(pkg, shown.packageName), sameTrack,
                shown.art != null, source != null, holdStartedAt == 0 ? 0 : now - holdStartedAt);

        switch (d) {
            case HOLD: {
                long left = HOLD_MS - (now - holdStartedAt);
                log("new track without artwork, holding previous for " + left + "ms: " + title);
                return new Result(shown.heldWithStateOf(live), Math.max(1, left));
            }
            case KEEP_ART:
                log("same track republished without artwork, keeping it: " + title);
                live = live.withArtOf(shown);
                break;
            default:
                break;
        }
        holdStartedAt = 0;
        shown = live;
        shownTrack = track;

        long recheck = 0;
        if (live.art == null && !TextUtils.isEmpty(title) && !track.equals(retriedTrack)) {
            retriedTrack = track;
            recheck = RETRY_MS;
            log("no artwork for " + title + "; showing fallback, rechecking in " + RETRY_MS + "ms");
        }
        return new Result(live, recheck);
    }

    /**
     * What to show when the widget's player has no session. Follow-active: nothing. Bound: the
     * player's last-known track (paused, not controllable) if we saw one, otherwise an idle state
     * that names the player. Never another app's metadata. Pure: unit-tested.
     */
    static NowPlaying withoutSession(@Nullable String boundPackage, NowPlaying shown) {
        if (boundPackage == null) return NowPlaying.NOTHING;
        if (boundPackage.equals(shown.packageName) && shown.title != null && shown.title.length() > 0) {
            return shown.asLastKnown();
        }
        return NowPlaying.idle(boundPackage);
    }

    private void reset() {
        shown = NowPlaying.NOTHING;
        shownTrack = null;
        holdStartedAt = 0;
    }

    // --- artwork ---

    private static final String[] BITMAP_KEYS = {
            MediaMetadata.METADATA_KEY_ALBUM_ART,
            MediaMetadata.METADATA_KEY_ART,
            MediaMetadata.METADATA_KEY_DISPLAY_ICON,
    };
    private static final String[] URI_KEYS = {
            MediaMetadata.METADATA_KEY_ALBUM_ART_URI,
            MediaMetadata.METADATA_KEY_ART_URI,
            MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI,
    };

    @Nullable
    private static Bitmap readArtwork(Context context, MediaMetadata meta) {
        for (String key : BITMAP_KEYS) {
            Bitmap b = meta.getBitmap(key);
            if (b != null && b.getWidth() > 0 && b.getHeight() > 0) return b;
        }
        for (String key : URI_KEYS) {
            String uri = meta.getString(key);
            if (TextUtils.isEmpty(uri)) continue;
            Bitmap b = loadLocalUri(context, Uri.parse(uri));
            if (b != null) {
                log("artwork from " + key);
                return b;
            }
        }
        return null;
    }

    /**
     * Local URIs only (content, android.resource, file). Radiation has no INTERNET permission by
     * design, so http(s) artwork URIs are skipped; players that use them also send a bitmap.
     */
    @Nullable
    private static Bitmap loadLocalUri(Context context, Uri uri) {
        String scheme = uri.getScheme();
        if (!ContentResolver.SCHEME_CONTENT.equals(scheme)
                && !ContentResolver.SCHEME_ANDROID_RESOURCE.equals(scheme)
                && !ContentResolver.SCHEME_FILE.equals(scheme)) {
            return null;
        }
        ContentResolver resolver = context.getContentResolver();
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream in = resolver.openInputStream(uri)) {
                BitmapFactory.decodeStream(in, null, bounds);
            }
            BitmapFactory.Options opts = new BitmapFactory.Options();
            int edge = Math.min(bounds.outWidth, bounds.outHeight);
            while (edge / (opts.inSampleSize * 2) >= ART_PX) opts.inSampleSize *= 2;
            try (InputStream in = resolver.openInputStream(uri)) {
                return BitmapFactory.decodeStream(in, null, opts);
            }
        } catch (Exception e) { // SecurityException, FileNotFound, ...: the player didn't grant access.
            log("artwork uri unreadable (" + e.getClass().getSimpleName() + "): " + uri);
            return null;
        }
    }

    /** Center-crop to an ART_PX square, then derive the seed color from a small copy. */
    private static Processed process(Bitmap source) {
        Bitmap src = source.getConfig() == Bitmap.Config.HARDWARE
                ? source.copy(Bitmap.Config.ARGB_8888, false) : source;
        int edge = Math.min(src.getWidth(), src.getHeight());
        int size = Math.min(ART_PX, edge);
        Rect crop = new Rect((src.getWidth() - edge) / 2, (src.getHeight() - edge) / 2,
                (src.getWidth() + edge) / 2, (src.getHeight() + edge) / 2);
        Bitmap art = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        new Canvas(art).drawBitmap(src, crop, new Rect(0, 0, size, size),
                new Paint(Paint.FILTER_BITMAP_FLAG));

        // No default filters: they drop near-black/white swatches, which monochrome art needs.
        List<Palette.Swatch> swatches = Palette.from(Bitmap.createScaledBitmap(art, 64, 64, true))
                .maximumColorCount(16).clearFilters().generate().getSwatches();
        int[] rgb = new int[swatches.size()];
        int[] population = new int[swatches.size()];
        for (int i = 0; i < rgb.length; i++) {
            rgb[i] = swatches.get(i).getRgb();
            population[i] = swatches.get(i).getPopulation();
        }
        return new Processed(art, ColorEngine.pickSeed(rgb, population));
    }

    /**
     * Cheap content identity: a new Bitmap object arrives with every metadata read, so object
     * identity is useless, and the track key alone can't tell "art arrived" from "no art".
     */
    private static String fingerprint(Bitmap b) {
        int hash = 17;
        for (int y = 0; y < 4; y++) {
            for (int x = 0; x < 4; x++) {
                int px = b.getConfig() == Bitmap.Config.HARDWARE ? 0
                        : b.getPixel((2 * x + 1) * b.getWidth() / 8, (2 * y + 1) * b.getHeight() / 8);
                hash = hash * 31 + px;
            }
        }
        return b.getWidth() + "x" + b.getHeight() + ":" + Integer.toHexString(hash);
    }

    @Nullable
    private static CharSequence firstNonEmpty(CharSequence a, CharSequence b) {
        return TextUtils.isEmpty(a) ? b : a;
    }

    static void log(String message) {
        if (BuildConfig.DEBUG) Log.d(TAG, message);
    }
}
