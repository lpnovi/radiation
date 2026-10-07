package com.lpnovi.radiation.media;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileOutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The last real track per binding ("" = follow active, else the bound package), for Idle mode's
 * Resume and Minimal content and for theming idle widgets with the last artwork.
 *
 * Persisted (text in preferences, artwork as a small PNG) so it survives Android restarting
 * Radiation's process. Written only when the track actually changes. A bound widget only ever
 * remembers its own player, so Resume never offers a different app.
 */
public final class LastPlayed {

    private static final String PREFS = "last_played";
    private static final Map<String, NowPlaying> memory = new HashMap<>();

    private LastPlayed() {}

    /** Records what a binding is showing, if it's a real track from a live session. */
    public static synchronized void remember(Context context, @Nullable String boundPackage, NowPlaying np) {
        if (!np.hasSession || np.packageName == null || np.title == null || np.title.length() == 0) return;
        String key = key(boundPackage);
        NowPlaying known = get(context, boundPackage);
        boolean sameTrack = Objects.equals(np.packageName, known.packageName)
                && Objects.equals(text(np.title), text(known.title)) && Objects.equals(text(np.artist), text(known.artist));
        if (sameTrack && Objects.equals(np.artKey, known.artKey)) return;

        NowPlaying last = np.asLastKnown();
        memory.put(key, last);
        prefs(context).edit()
                .putString(key + ".pkg", np.packageName)
                .putString(key + ".title", text(np.title))
                .putString(key + ".artist", text(np.artist))
                .putString(key + ".artKey", np.artKey)
                .putInt(key + ".seed", np.seed)
                .apply();
        File file = artFile(context, key);
        if (np.art == null) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        } else {
            try (FileOutputStream out = new FileOutputStream(file)) {
                np.art.compress(Bitmap.CompressFormat.PNG, 100, out);
            } catch (Exception e) {
                // Artwork is a nicety; the text is what matters.
            }
        }
    }

    /** The binding's last-known track (paused, not controllable), or {@link NowPlaying#NOTHING}. */
    public static synchronized NowPlaying get(Context context, @Nullable String boundPackage) {
        String key = key(boundPackage);
        NowPlaying cached = memory.get(key);
        if (cached != null) return cached;
        SharedPreferences p = prefs(context);
        String pkg = p.getString(key + ".pkg", null);
        NowPlaying np = NowPlaying.NOTHING;
        if (pkg != null) {
            File file = artFile(context, key);
            Bitmap art = file.exists() ? BitmapFactory.decodeFile(file.getPath()) : null;
            np = new NowPlaying(pkg, p.getString(key + ".title", null), p.getString(key + ".artist", null), art,
                    art == null ? null : p.getString(key + ".artKey", null), p.getInt(key + ".seed", 0),
                    false, Shuffle.UNSUPPORTED, null, false, false);
        }
        memory.put(key, np);
        return np;
    }

    private static String key(@Nullable String boundPackage) {
        return boundPackage == null ? "active" : "bound." + boundPackage;
    }

    @Nullable
    private static String text(@Nullable CharSequence s) {
        return s == null ? null : s.toString();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static File artFile(Context context, String key) {
        File dir = new File(context.getFilesDir(), "last_played");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return new File(dir, key.replaceAll("[^A-Za-z0-9._]", "_") + ".png");
    }
}
