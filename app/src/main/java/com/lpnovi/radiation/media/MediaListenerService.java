package com.lpnovi.radiation.media;

import android.app.WallpaperManager;
import android.content.ComponentName;
import android.content.res.Configuration;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.lpnovi.radiation.widget.WidgetRenderer;
import com.lpnovi.radiation.widget.WidgetUpdater;

import java.util.ArrayList;
import java.util.List;

/**
 * Exists because Android only exposes other apps' media sessions to enabled notification listeners.
 * Of notifications, only players' media notifications are looked at, and only for their buttons
 * (a shuffle button some players expose nowhere else); nothing is stored or sent. While bound, it requests a render on every session,
 * metadata or playback change instead of polling. All callbacks are unregistered on disconnect.
 */
public class MediaListenerService extends NotificationListenerService {

    private final Handler handler = new Handler(Looper.getMainLooper());
    private MediaSessionManager sessionManager;
    private final List<MediaController> watched = new ArrayList<>();
    private final List<Shuffle.Watch> shuffleWatches = new ArrayList<>();

    private final MediaController.Callback callback = new MediaController.Callback() {
        @Override
        public void onPlaybackStateChanged(@Nullable PlaybackState state) {
            render();
        }

        @Override
        public void onMetadataChanged(@Nullable MediaMetadata metadata) {
            render();
        }

        @Override
        public void onSessionDestroyed() {
            render();
        }
    };

    private final MediaSessionManager.OnActiveSessionsChangedListener sessionsListener = this::watch;

    private final WallpaperManager.OnColorsChangedListener wallpaperListener = (colors, which) -> {
        WidgetRenderer.invalidateWallpaper();
        render();
    };

    @Override
    public void onListenerConnected() {
        ComponentName self = new ComponentName(this, MediaListenerService.class);
        sessionManager = getSystemService(MediaSessionManager.class);
        sessionManager.addOnActiveSessionsChangedListener(sessionsListener, self, handler);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            WallpaperManager.getInstance(this).addOnColorsChangedListener(wallpaperListener, handler);
        }
        try {
            for (StatusBarNotification n : getActiveNotifications()) Shuffle.onNotification(n, false);
        } catch (RuntimeException ignored) {
            // The listener can be unbound again before this call lands.
        }
        watch(sessionManager.getActiveSessions(self));
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (Shuffle.onNotification(sbn, false)) render();
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        if (Shuffle.onNotification(sbn, true)) render();
    }

    @Override
    public void onListenerDisconnected() {
        release();
        render();
    }

    @Override
    public void onDestroy() {
        release();
        super.onDestroy();
    }

    /** Dark mode and Material You palette changes arrive as configuration changes. */
    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        WidgetRenderer.invalidateWallpaper();
        render();
    }

    private void release() {
        if (sessionManager != null) {
            sessionManager.removeOnActiveSessionsChangedListener(sessionsListener);
            sessionManager = null;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            WallpaperManager.getInstance(this).removeOnColorsChangedListener(wallpaperListener);
        }
        watch(null);
    }

    /** Re-registers on the current session list; controllers from the old list are released. */
    private void watch(@Nullable List<MediaController> controllers) {
        for (MediaController c : watched) c.unregisterCallback(callback);
        for (Shuffle.Watch w : shuffleWatches) w.release();
        watched.clear();
        shuffleWatches.clear();
        List<MediaSession.Token> live = new ArrayList<>();
        if (controllers != null) {
            for (MediaController c : controllers) {
                c.registerCallback(callback, handler);
                watched.add(c);
                shuffleWatches.add(new Shuffle.Watch(this, c, handler, this::render));
                live.add(c.getSessionToken());
            }
        }
        Shuffle.retainOnly(live);
        render();
    }

    private void render() {
        WidgetUpdater.request(this);
    }
}
