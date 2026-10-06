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
import java.util.List;

/**
 * The single path by which widgets get updated. Event-driven: callers request a render; requests
 * arriving within THROTTLE_MS collapse into one. All media reads, artwork decoding and Palette
 * work happen on one background thread.
 */
public final class WidgetUpdater {

    private static final long THROTTLE_MS = 40;

    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final NowPlayingTracker tracker = new NowPlayingTracker();
    private static final List<BroadcastReceiver.PendingResult> waiting = new ArrayList<>();
    private static Handler worker;
    @SuppressLint("StaticFieldLeak") // always the application context
    private static Context app;
    private static long scheduledAt = Long.MAX_VALUE;

    private static volatile NowPlaying latest = NowPlaying.NOTHING;
    private static volatile Runnable listener;

    private WidgetUpdater() {}

    public static void request(Context context) {
        schedule(context, THROTTLE_MS, null);
    }

    /** For broadcast receivers: keeps the process alive (via goAsync) until the render lands. */
    public static void request(Context context, BroadcastReceiver.PendingResult async) {
        schedule(context, THROTTLE_MS, async);
    }

    /** What widgets currently show; the studio preview renders from this. */
    public static NowPlaying latest() {
        return latest;
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
            NowPlayingTracker.Result result = tracker.resolve(context);
            latest = result.nowPlaying;
            if (result.recheckInMs > 0) schedule(context, result.recheckInMs, null);

            renderAll(context, result.nowPlaying);
            Runnable l = listener;
            if (l != null) main.post(l);
        } catch (RuntimeException e) {
            // A misbehaving player (odd metadata, dead binder) must not take the listener process down.
            android.util.Log.e("Radiation", "render failed", e);
        } finally {
            for (BroadcastReceiver.PendingResult r : done) r.finish();
        }
    }

    private static void renderAll(Context context, NowPlaying np) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        boolean access = MediaSessions.hasAccess(context);
        for (int id : widgetIds(context)) {
            manager.updateAppWidget(id, WidgetRenderer.build(context, id, WidgetConfig.load(context, id),
                    np, access, WidgetRenderer.heightDp(context, manager, id)));
        }
    }

    /**
     * Immediate feedback for a tap: render the expected state now (pause glyph, visualizer, shuffle)
     * instead of waiting for the player's round-trip. The player's real state arrives moments
     * later and wins. Skipped when there's no known session to base it on.
     */
    static void showOptimistic(Context context, NowPlaying expected) {
        if (expected.packageName == null) return;
        latest = expected;
        renderAll(context, expected);
    }

    public static int[] widgetIds(Context context) {
        return AppWidgetManager.getInstance(context)
                .getAppWidgetIds(new ComponentName(context, RadiationWidgetProvider.class));
    }
}
