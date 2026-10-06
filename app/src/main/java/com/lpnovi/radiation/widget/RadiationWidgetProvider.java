package com.lpnovi.radiation.widget;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;

import com.lpnovi.radiation.config.WidgetConfig;
import com.lpnovi.radiation.media.MediaSessions;

public class RadiationWidgetProvider extends AppWidgetProvider {

    static final String ACTION_CONTROL = "com.lpnovi.radiation.action.CONTROL";
    static final String EXTRA_ACTION = "action";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (ACTION_CONTROL.equals(intent.getAction())) {
            String name = intent.getStringExtra(EXTRA_ACTION);
            if (name != null) {
                MediaSessions.perform(context, MediaSessions.Action.valueOf(name));
            }
            return;
        }
        super.onReceive(context, intent);
    }

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] appWidgetIds) {
        WidgetRenderer.updateAll(context);
    }

    @Override
    public void onDeleted(Context context, int[] appWidgetIds) {
        for (int id : appWidgetIds) WidgetConfig.delete(context, id);
    }
}
