package com.lpnovi.radiation.media;

import android.content.ComponentName;
import android.content.res.Configuration;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.service.notification.NotificationListenerService;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.lpnovi.radiation.widget.WidgetRenderer;

import java.util.ArrayList;
import java.util.List;

/**
 * Exists because Android only exposes other apps' media sessions to enabled notification listeners.
 * Notifications themselves are ignored. While bound, it re-renders widgets on session changes
 * instead of polling.
 */
public class MediaListenerService extends NotificationListenerService {

    private MediaSessionManager sessionManager;
    private final List<MediaController> watched = new ArrayList<>();

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

    @Override
    public void onListenerConnected() {
        ComponentName self = new ComponentName(this, MediaListenerService.class);
        sessionManager = getSystemService(MediaSessionManager.class);
        sessionManager.addOnActiveSessionsChangedListener(sessionsListener, self);
        watch(sessionManager.getActiveSessions(self));
    }

    @Override
    public void onListenerDisconnected() {
        if (sessionManager != null) sessionManager.removeOnActiveSessionsChangedListener(sessionsListener);
        watch(null);
    }

    /** Dark mode and Material You palette changes arrive as configuration changes. */
    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        render();
    }

    private void watch(@Nullable List<MediaController> controllers) {
        for (MediaController c : watched) c.unregisterCallback(callback);
        watched.clear();
        if (controllers != null) {
            for (MediaController c : controllers) {
                c.registerCallback(callback);
                watched.add(c);
            }
        }
        render();
    }

    private void render() {
        WidgetRenderer.updateAll(this);
    }
}
