package com.lpnovi.radiation.config;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Settings for one widget instance, persisted under keys prefixed with its appWidgetId.
 *
 * SharedPreferences rather than DataStore: widget rendering runs inside broadcast receivers and
 * needs cheap synchronous reads. To add a setting, add a field plus one line in load() and save().
 * Defaults are the "clean" preset: every new option starts in its most restrained state.
 */
public final class WidgetConfig {

    /** What the surface layer is made of. */
    public enum Background {
        /** Near-black carrying a hint of the artwork's hue. Default. */
        ALBUM_TINT,
        /** Album-colored glow on the art side, fading into the tinted near-black. */
        ALBUM_GRADIENT,
        AMOLED,
        MATERIAL_YOU,
        /** Light haze over the wallpaper plus a hairline edge. Real blur isn't available to widgets. */
        GLASS,
        CUSTOM,
    }

    /** How album-derived colors are treated: deep and rich (default), or light and soft. */
    public enum AlbumTone { RICH, PASTEL }

    /** Transport icon family. ROUNDED is the original set. */
    public enum IconStyle { ROUNDED, SHARP, LINE, BOLD }

    /** Color of the controls and visualizer. */
    public enum Accent { ALBUM, MATERIAL_YOU, MONO }

    /** Decorative only: true audio reactivity isn't viable for a widget (see docs/platform-notes.md). */
    public enum Visualizer { OFF, BARS, WAVE }

    public enum ArtShape { ROUNDED, CIRCLE }

    public enum TapAction { ACTIVE_APP, SPOTIFY, NOTHING }

    public Background background = Background.ALBUM_TINT;
    public int customColor = 0xFF1C1B1F;
    /** Background layer only, 0 (transparent) – 255 (opaque). Never applied to foreground. */
    public int backgroundAlpha = 255;
    public boolean outline = false;
    public Accent accent = Accent.ALBUM;
    public AlbumTone albumTone = AlbumTone.RICH;
    public IconStyle iconStyle = IconStyle.ROUNDED;
    public Visualizer visualizer = Visualizer.BARS;
    public boolean showArt = true;
    public ArtShape artShape = ArtShape.ROUNDED;
    public boolean showArtist = true;
    public boolean showPrevious = true;
    public boolean showNext = true;
    public boolean showShuffle = false;
    public TapAction tapAction = TapAction.ACTIVE_APP;

    /** 0% = transparent background, 100% = fully opaque background. */
    public static int alphaFromPercent(int percent) {
        return Math.round(Math.max(0, Math.min(100, percent)) * 255 / 100f);
    }

    public static int percentFromAlpha(int alpha) {
        return Math.round(Math.max(0, Math.min(255, alpha)) * 100 / 255f);
    }

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
        c.applyLegacyStyle(p.getString(k + "style", null));
        c.background = parse(Background.class, p.getString(k + "background", null), c.background);
        c.customColor = p.getInt(k + "customColor", c.customColor);
        c.backgroundAlpha = p.getInt(k + "backgroundAlpha", c.backgroundAlpha);
        c.outline = p.getBoolean(k + "outline", c.outline);
        c.accent = parse(Accent.class, p.getString(k + "accent", null), c.accent);
        c.albumTone = parse(AlbumTone.class, p.getString(k + "albumTone", null), c.albumTone);
        c.iconStyle = parse(IconStyle.class, p.getString(k + "iconStyle", null), c.iconStyle);
        c.visualizer = parse(Visualizer.class, p.getString(k + "visualizer", null), c.visualizer);
        c.showArt = p.getBoolean(k + "showArt", c.showArt);
        c.artShape = parse(ArtShape.class, p.getString(k + "artShape", null), c.artShape);
        c.showArtist = p.getBoolean(k + "showArtist", c.showArtist);
        c.showPrevious = p.getBoolean(k + "showPrevious", c.showPrevious);
        c.showNext = p.getBoolean(k + "showNext", c.showNext);
        c.showShuffle = p.getBoolean(k + "showShuffle", c.showShuffle);
        c.tapAction = parse(TapAction.class, p.getString(k + "tapAction", null), c.tapAction);
        return c;
    }

    public void save(Context context, int appWidgetId) {
        String k = prefix(appWidgetId);
        prefs(context).edit()
                .remove(k + "style") // superseded by background + accent
                .putString(k + "background", background.name())
                .putInt(k + "customColor", customColor)
                .putInt(k + "backgroundAlpha", backgroundAlpha)
                .putBoolean(k + "outline", outline)
                .putString(k + "accent", accent.name())
                .putString(k + "albumTone", albumTone.name())
                .putString(k + "iconStyle", iconStyle.name())
                .putString(k + "visualizer", visualizer.name())
                .putBoolean(k + "showArt", showArt)
                .putString(k + "artShape", artShape.name())
                .putBoolean(k + "showArtist", showArtist)
                .putBoolean(k + "showPrevious", showPrevious)
                .putBoolean(k + "showNext", showNext)
                .putBoolean(k + "showShuffle", showShuffle)
                .putString(k + "tapAction", tapAction.name())
                .apply();
    }

    /** Before 0.3 a single "style" chose both surface and controls; split it without changing looks. */
    void applyLegacyStyle(String style) {
        if (style == null) return;
        switch (style) {
            case "AMOLED": background = Background.AMOLED; accent = Accent.MONO; break;
            case "MATERIAL_YOU": background = Background.MATERIAL_YOU; accent = Accent.MATERIAL_YOU; break;
            default: background = Background.ALBUM_TINT; accent = Accent.ALBUM; break;
        }
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
    static <E extends Enum<E>> E parse(Class<E> type, String name, E fallback) {
        if (name == null) return fallback;
        try {
            return Enum.valueOf(type, name);
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }
}
