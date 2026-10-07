package com.lpnovi.radiation.media;

import android.content.ComponentName;
import android.content.Context;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Build;
import android.util.Log;

import com.lpnovi.radiation.BuildConfig;
import com.lpnovi.radiation.config.WidgetConfig;
import com.lpnovi.radiation.widget.WidgetUpdater;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * A plain-text report a user can copy from the Studio and send: device, access, widgets, what each
 * player's media session exposes, and recent shuffle events. Kept in memory only, never written or
 * sent anywhere by Radiation. No track titles, artists or artwork are included.
 */
public final class Diagnostics {

    static final String TAG = "RadiationShuffle";
    private static final int MAX_EVENTS = 60;
    private static final ArrayDeque<String> events = new ArrayDeque<>();

    private Diagnostics() {}

    static void event(String message) {
        if (BuildConfig.DEBUG) Log.d(TAG, message);
        String line = new SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(new Date()) + "  " + message;
        synchronized (events) {
            if (events.size() == MAX_EVENTS) events.removeFirst();
            events.addLast(line);
        }
    }

    public static String report(Context context) {
        StringBuilder r = new StringBuilder("Radiation diagnostic report\n");
        r.append("Radiation ").append(BuildConfig.VERSION_NAME).append(" (").append(BuildConfig.VERSION_CODE)
                .append(BuildConfig.DEBUG ? ", debug" : "").append(")\n");
        r.append("Android ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append("), ")
                .append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n');
        r.append("Language ").append(Locale.getDefault().toLanguageTag()).append('\n');

        int[] ids = WidgetUpdater.widgetIds(context);
        r.append("\nWidgets: ").append(ids.length).append('\n');
        for (int id : ids) {
            WidgetConfig c = WidgetConfig.load(context, id);
            r.append("  #").append(id).append(" player=")
                    .append(c.boundPackage == null ? "follow active" : c.boundPackage)
                    .append(" shuffleButton=").append(c.showShuffle).append('\n');
        }

        r.append("\nMedia sessions:\n");
        try {
            List<MediaController> sessions = context.getSystemService(MediaSessionManager.class)
                    .getActiveSessions(new ComponentName(context, MediaListenerService.class));
            if (sessions.isEmpty()) r.append("  none (open a player and start playback)\n");
            for (MediaController c : sessions) {
                PlaybackState ps = c.getPlaybackState();
                r.append("  ").append(Shuffle.describe(c))
                        .append(" playbackState=").append(ps == null ? "none" : ps.getState()).append('\n');
            }
        } catch (SecurityException e) {
            r.append("  unavailable: notification access is off\n");
        }

        r.append("\nRecent shuffle events (oldest first):\n");
        synchronized (events) {
            if (events.isEmpty()) r.append("  none yet\n");
            for (String e : events) r.append("  ").append(e).append('\n');
        }
        return r.toString();
    }
}
