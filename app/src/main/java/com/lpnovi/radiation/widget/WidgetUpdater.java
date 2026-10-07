package com.lpnovi.radiation.widget;

import android.annotation.SuppressLint;
import android.appwidget.AppWidgetManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;

import androidx.annotation.Nullable;

import com.lpnovi.radiation.config.WidgetConfig;
import com.lpnovi.radiation.media.MediaSessions;
import com.lpnovi.radiation.media.NowPlaying;
import com.lpnovi.radiation.media.NowPlayingTracker;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The single path by which widgets get updated. Event-driven: callers request a render; requests
 * arriving within THROTTLE_MS collapse into one. All media reads, artwork decoding and Palette
 * work happen on one background thread.
 */
public final class WidgetUpdater {

    private static final long THROTTLE_MS = 40;

    private static final Handler main = new Handler(Looper.getMainLooper());
    /** One tracker per binding in use ("" = follow active). Render thread only. */
    private static final Map<String, NowPlayingTracker> trackers = new HashMap<>();
    private static final List<BroadcastReceiver.PendingResult> waiting = new ArrayList<>();
    private static Handler worker;
    @SuppressLint("StaticFieldLeak") // always the application context
    private static Context app;
    private static long scheduledAt = Long.MAX_VALUE;

    private static final Map<String, NowPlaying> latest = new ConcurrentHashMap<>();
    private static volatile Runnable listener;

    private WidgetUpdater() {}

    public static void request(Context context) {
        schedule(context, THROTTLE_MS, null);
    }

    /** For broadcast receivers: keeps the process alive (via goAsync) until the render lands. */
    public static void request(Context context, BroadcastReceiver.PendingResult async) {
        schedule(context, THROTTLE_MS, async);
    }

    /** What widgets with this binding currently show; the studio preview renders from this. */
    public static NowPlaying latest(@Nullable String boundPackage) {
        NowPlaying np = latest.get(key(boundPackage));
        return np == null ? NowPlaying.NOTHING : np;
    }

    private static String key(@Nullable String boundPackage) {
        return boundPackage == null ? "" : boundPackage;
    }

    /** Main-thread callback after each render (the studio preview). Pass null to clear. */
    public static void setListener(@Nullable Runnable r) {
        listener = r;
    }

    private static synchronized void schedule(Context context, long delayMs,
                                              @Nullable BroadcastReceiver.PendingResult async) {
        app = context.getApplicationContext();
        if (worker == null) {
            HandlerThread t = new HandlerThread("radiation-render", Process.THREAD_PRIORITY_BACKGROUND);
            t.start();
            worker = new Handler(t.getLooper());
        }
        if (async != null) waiting.add(async);
        long at = SystemClock.uptimeMillis() + delayMs;
        if (at >= scheduledAt) return; // an earlier render is already coming
        worker.removeCallbacks(RENDER);
        scheduledAt = at;
        worker.postAtTime(RENDER, at);
    }

    private static final Runnable RENDER = WidgetUpdater::render;

    private static void render() {
        Context context;
        List<BroadcastReceiver.PendingResult> done;
        synchronized (WidgetUpdater.class) {
            scheduledAt = Long.MAX_VALUE;
            context = app;
            done = new ArrayList<>(waiting);
            waiting.clear();
        }
        try {
            // Resolve each binding in use once, then render every widget from its own binding.
            int[] ids = widgetIds(context);
            WidgetConfig[] configs = new WidgetConfig[ids.length];
            Map<String, NowPlaying> resolved = new HashMap<>();
            for (int i = 0; i < ids.length; i++) {
                configs[i] = WidgetConfig.load(context, ids[i]);
                String k = key(configs[i].boundPackage);
                if (resolved.containsKey(k)) continue;
                NowPlayingTracker tracker = trackers.get(k);
                if (tracker == null) trackers.put(k, tracker = new NowPlayingTracker());
                NowPlayingTracker.Result result = tracker.resolve(context, configs[i].boundPackage);
                if (result.recheckInMs > 0) schedule(context, result.recheckInMs, null);
                resolved.put(k, result.nowPlaying);
            }
            trackers.keySet().retainAll(resolved.keySet());
            latest.keySet().retainAll(resolved.keySet());
            latest.putAll(resolved);
            renderAll(context, ids, configs, null, null);
            Runnable l = listener;
            if (l != null) main.post(l);
        } catch (RuntimeException e) {
            // A misbehaving player (odd metadata, dead binder) must not take the listener process down.
            android.util.Log.e("Radiation", "render failed", e);
        } finally {
            for (BroadcastReceiver.PendingResult r : done) r.finish();
        }
    }

    /** Renders widgets; with onlyKey set, only those with that binding, showing override. */
    private static void renderAll(Context context, int[] ids, WidgetConfig[] configs,
                                  @Nullable String onlyKey, @Nullable NowPlaying override) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        boolean access = MediaSessions.hasAccess(context);
        for (int i = 0; i < ids.length; i++) {
            String k = key(configs[i].boundPackage);
            if (onlyKey != null && !onlyKey.equals(k)) continue;
            NowPlaying np = override != null ? override : latest(configs[i].boundPackage);
            manager.updateAppWidget(ids[i], WidgetRenderer.build(context, ids[i], configs[i], np, access,
                    WidgetRenderer.widthDp(context, manager, ids[i]), WidgetRenderer.heightDp(context, manager, ids[i])));
        }
    }

    /**
     * Immediate feedback for a tap: render the expected state now (pause glyph, visualizer, shuffle)
     * instead of waiting for the player's round-trip. The player's real state arrives moments
     * later and wins. Skipped when there's no known session to base it on.
     */
    static void showOptimistic(Context context, @Nullable String boundPackage, NowPlaying expected) {
        if (expected.packageName == null || !expected.hasSession) return;
        latest.put(key(boundPackage), expected);
        int[] ids = widgetIds(context);
        WidgetConfig[] configs = new WidgetConfig[ids.length];
        for (int i = 0; i < ids.length; i++) configs[i] = WidgetConfig.load(context, ids[i]);
        renderAll(context, ids, configs, key(boundPackage), expected);
    }

    public static int[] widgetIds(Context context) {
        return AppWidgetManager.getInstance(context)
                .getAppWidgetIds(new ComponentName(context, RadiationWidgetProvider.class));
    }
}
