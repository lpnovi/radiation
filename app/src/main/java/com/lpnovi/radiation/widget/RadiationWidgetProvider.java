package com.lpnovi.radiation.widget;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.media.session.MediaController;
import android.os.Bundle;

import com.lpnovi.radiation.config.WidgetConfig;
import com.lpnovi.radiation.media.MediaSessions;

public class RadiationWidgetProvider extends AppWidgetProvider {

    static final String ACTION_CONTROL = "com.lpnovi.radiation.action.CONTROL";
    static final String EXTRA_ACTION = "action";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (ACTION_CONTROL.equals(intent.getAction())) {
            String name = intent.getStringExtra(EXTRA_ACTION);
            if (name == null) return;
            MediaSessions.Action action = MediaSessions.Action.valueOf(name);
            if (action == MediaSessions.Action.PLAY_PAUSE) {
                MediaController c = MediaSessions.active(context);
                if (c != null) WidgetUpdater.showOptimisticPlayState(context, !MediaSessions.isPlaying(c));
            }
            MediaSessions.perform(context, action);
            return;
        }
        super.onReceive(context, intent);
    }

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] appWidgetIds) {
        WidgetUpdater.request(context, goAsync());
    }

    /** Resized, or moved to a launcher with different cell sizes. */
    @Override
    public void onAppWidgetOptionsChanged(Context context, AppWidgetManager manager, int appWidgetId,
                                          Bundle newOptions) {
        WidgetUpdater.request(context, goAsync());
    }

    @Override
    public void onDeleted(Context context, int[] appWidgetIds) {
        for (int id : appWidgetIds) WidgetConfig.delete(context, id);
    }
}
