package com.lpnovi.radiation.config;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Settings for one widget instance, persisted under keys prefixed with its appWidgetId.
 *
 * SharedPreferences rather than DataStore: widget rendering runs inside broadcast receivers and
 * needs cheap synchronous reads. To add a setting, add a field plus one line in load() and save().
 */
public final class WidgetConfig {

    public enum Style { AMOLED, MATERIAL_YOU }

    public enum TapAction { ACTIVE_APP, SPOTIFY, NOTHING }

    public Style style = Style.AMOLED;
    /** 0–255. */
    public int backgroundAlpha = 255;
    public boolean showArt = true;
    public boolean showPrevious = true;
    public boolean showNext = true;
    public TapAction tapAction = TapAction.ACTIVE_APP;

    private static final String PREFS = "widgets";

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String prefix(int appWidgetId) {
        return "w" + appWidgetId + ".";
    }

    public static WidgetConfig load(Context context, int appWidgetId) {
        SharedPreferences p = prefs(context);
        String k = prefix(appWidgetId);
        WidgetConfig c = new WidgetConfig();
        c.style = parse(Style.class, p.getString(k + "style", null), c.style);
        c.backgroundAlpha = p.getInt(k + "backgroundAlpha", c.backgroundAlpha);
        c.showArt = p.getBoolean(k + "showArt", c.showArt);
        c.showPrevious = p.getBoolean(k + "showPrevious", c.showPrevious);
        c.showNext = p.getBoolean(k + "showNext", c.showNext);
        c.tapAction = parse(TapAction.class, p.getString(k + "tapAction", null), c.tapAction);
        return c;
    }

    public void save(Context context, int appWidgetId) {
        String k = prefix(appWidgetId);
        prefs(context).edit()
                .putString(k + "style", style.name())
                .putInt(k + "backgroundAlpha", backgroundAlpha)
                .putBoolean(k + "showArt", showArt)
                .putBoolean(k + "showPrevious", showPrevious)
                .putBoolean(k + "showNext", showNext)
                .putString(k + "tapAction", tapAction.name())
                .apply();
    }

    public static void delete(Context context, int appWidgetId) {
        SharedPreferences p = prefs(context);
        SharedPreferences.Editor e = p.edit();
        String k = prefix(appWidgetId);
        for (String key : p.getAll().keySet()) {
            if (key.startsWith(k)) e.remove(key);
        }
        e.apply();
    }

    /** Unknown names (e.g. a value removed in a later version) fall back to the default. */
    private static <E extends Enum<E>> E parse(Class<E> type, String name, E fallback) {
        if (name == null) return fallback;
        try {
            return Enum.valueOf(type, name);
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }
}
