package com.lpnovi.radiation.widget;

import android.app.PendingIntent;
import android.app.WallpaperColors;
import android.app.WallpaperManager;
import android.appwidget.AppWidgetManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.View;
import android.widget.RemoteViews;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import com.lpnovi.radiation.R;
import com.lpnovi.radiation.StudioActivity;
import com.lpnovi.radiation.color.ColorEngine;
import com.lpnovi.radiation.config.WidgetConfig;
import com.lpnovi.radiation.media.MediaSessions;
import com.lpnovi.radiation.media.NowPlaying;
import com.lpnovi.radiation.media.Shuffle;

/**
 * (NowPlaying, per-widget config, cell height) -> RemoteViews. Shared by the home-screen widget
 * and the studio preview. Sets every dynamic property on every call, because hosts may reapply
 * an update onto the previous view tree.
 */
public final class WidgetRenderer {

    static final float DEFAULT_HEIGHT_DP = 80;
    static final float DEFAULT_WIDTH_DP = 320;

    private WidgetRenderer() {}

    public static RemoteViews build(Context context, int appWidgetId, WidgetConfig config,
                                    NowPlaying np, boolean hasAccess, float widthDp, float heightDp) {
        RemoteViews v = new RemoteViews(context.getPackageName(), R.layout.widget_radiation);
        ColorEngine.Inputs in = new ColorEngine.Inputs();
        in.seed = np.seed;
        in.wallpaper = wallpaperColor(context);
        in.myBg = context.getColor(R.color.my_bg);
        in.myAccent = context.getColor(R.color.my_control);
        ColorEngine.Theme t = ColorEngine.theme(config, in);
        Sizes s = Sizes.forHeight(heightDp);

        // Background layers: the only things background opacity affects. Album Glow replaces the
        // plain surface with one dithered bitmap (see GlowBackground) rather than layering on it.
        boolean glow = t.glow != ColorEngine.NONE;
        v.setViewVisibility(R.id.surface, glow ? View.GONE : View.VISIBLE);
        v.setInt(R.id.surface, "setColorFilter", t.surface);
        v.setInt(R.id.surface, "setImageAlpha", config.backgroundAlpha);
        v.setViewVisibility(R.id.glow_box, glow ? View.VISIBLE : View.GONE);
        if (glow) {
            v.setImageViewBitmap(R.id.glow, GlowBackground.get(Math.round(widthDp), Math.round(heightDp),
                    t.surface, t.glow, Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? 0 : LEGACY_RADIUS_DP));
        }
        v.setInt(R.id.glow, "setImageAlpha", config.backgroundAlpha);
        // Border: a hairline of the text color (white on dark, black on light) at low strength, so it
        // defines the edge without graying the surface. Slightly stronger when the surface is
        // translucent, where it is the only thing outlining the widget.
        boolean glass = config.background == WidgetConfig.Background.GLASS;
        v.setViewVisibility(R.id.surface_outline, config.outline || glass ? View.VISIBLE : View.GONE);
        v.setImageViewResource(R.id.surface_outline, t.darkForeground ? R.drawable.border_dark : R.drawable.border_light);
        v.setInt(R.id.surface_outline, "setImageAlpha", glass ? 0x4D
                : Math.round(BORDER_ALPHA_CLEAR + (BORDER_ALPHA_OPAQUE - BORDER_ALPHA_CLEAR) * config.backgroundAlpha / 255f));

        // With a visualizer, lift the content slightly so the strip has its own space at the bottom.
        float liftDp = config.visualizer == WidgetConfig.Visualizer.OFF ? 0 : 5;
        float endPadDp = Math.max(4, s.pad - 6);
        // The corner shuffle sits in the column of the rightmost transport control.
        float columnDp = endPadDp + (config.showNext ? s.sideWidth : s.playWidth) / 2f;
        Corner corner = Corner.place(heightDp, liftDp, config.showNext ? s.sideIcon : s.playIcon, columnDp);

        // Proportions from the real cell height (Android 12+; older launchers use the XML defaults).
        // Controls are a touch narrower than tall: full-height targets, more width for the title.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            size(v, R.id.art_box, s.art, s.art);
            size(v, R.id.play_pause, s.playWidth, s.play);
            size(v, R.id.previous, s.sideWidth, s.sideTouch);
            size(v, R.id.next, s.sideWidth, s.sideTouch);
            size(v, R.id.shuffle, Corner.WIDTH, corner.height);
            v.setViewLayoutMargin(R.id.shuffle, RemoteViews.MARGIN_END, corner.marginEnd, TypedValue.COMPLEX_UNIT_DIP);
        }
        int pad = px(context, s.pad);
        v.setViewPadding(R.id.row, pad, 0, px(context, endPadDp), px(context, liftDp));
        int sideX = px(context, (s.sideWidth - s.sideIcon) / 2f), sideY = px(context, (s.sideTouch - s.sideIcon) / 2f);
        v.setViewPadding(R.id.previous, sideX, sideY, sideX, sideY);
        v.setViewPadding(R.id.next, sideX, sideY, sideX, sideY);
        int cornerX = px(context, (Corner.WIDTH - corner.glyph) / 2f);
        v.setViewPadding(R.id.shuffle, cornerX, px(context, corner.glyphTop), cornerX,
                px(context, Math.max(0, corner.height - corner.glyphTop - corner.glyph)));
        int playX = px(context, (s.playWidth - s.playIcon) / 2f), playY = px(context, (s.play - s.playIcon) / 2f);
        v.setViewPadding(R.id.play_pause, playX, playY, playX, playY);
        v.setViewPadding(R.id.info, px(context, config.showArt ? INFO_START_DP : 0), 0, px(context, INFO_END_DP), 0);

        // Text content. A bound player with nothing to show names itself rather than borrowing
        // another app's metadata.
        boolean bound = config.boundPackage != null;
        boolean idle = TextUtils.isEmpty(np.title);
        CharSequence title, artist;
        if (!idle) {
            title = np.title;
            artist = np.artist == null ? "" : np.artist;
        } else if (!hasAccess) {
            title = context.getText(R.string.nothing_playing);
            artist = context.getText(R.string.tap_to_connect);
        } else if (bound) {
            title = Players.label(context, config.boundPackage, config.boundLabel);
            artist = context.getText(Players.isInstalled(context, config.boundPackage)
                    ? R.string.player_not_playing : R.string.player_not_installed);
        } else {
            title = context.getText(R.string.nothing_playing);
            artist = context.getText(R.string.app_name);
        }

        // Typography: fit the block to the row height, then let long titles shrink a little.
        float fontScale = context.getResources().getConfiguration().fontScale;
        boolean forceArtist = idle && !hasAccess; // the "tap to connect" hint always shows
        Typography ty = Typography.fitHeight(config.showTitle, config.titleSize, config.titleLines,
                config.showArtist || forceArtist, config.artistSize, heightDp - liftDp - TEXT_BREATHING_DP, fontScale);
        float titleSp = ty.titleSp;
        if (config.fitTitle && ty.showTitle) {
            float availableDp = widthDp - s.pad - (config.showArt ? s.art + INFO_START_DP : 0) - INFO_END_DP
                    - (config.showPrevious ? s.sideWidth : 0) - s.playWidth - (config.showNext ? s.sideWidth : 0)
                    - endPadDp;
            titleSp = Typography.fitWidth(ty.titleSp, ty.titleLines, px(context, Math.max(40, availableDp)),
                    measure(context, title, config.titleWeight));
        }
        int titleView = TITLE_VIEWS[config.titleWeight.ordinal()];
        for (int id : TITLE_VIEWS) {
            v.setViewVisibility(id, ty.showTitle && id == titleView ? View.VISIBLE : View.GONE);
            v.setTextViewText(id, title);
            v.setTextColor(id, t.text);
            v.setTextViewTextSize(id, TypedValue.COMPLEX_UNIT_SP, titleSp);
            v.setInt(id, "setMaxLines", ty.titleLines);
        }
        int artistView = config.artistWeight == WidgetConfig.Weight.REGULAR ? R.id.artist : R.id.artist_medium;
        for (int id : new int[]{R.id.artist, R.id.artist_medium}) {
            v.setViewVisibility(id, ty.showArtist && id == artistView ? View.VISIBLE : View.GONE);
            v.setTextViewText(id, artist);
            v.setTextColor(id, t.textSecondary);
            // Only when the title shrank to fit; a deliberate size choice is left alone.
            v.setTextViewTextSize(id, TypedValue.COMPLEX_UNIT_SP, ty.showTitle && titleSp < ty.titleSp
                    ? Typography.artistFor(titleSp, ty.artistSp) : ty.artistSp);
        }

        // Artwork vs fallback tile: separate views, so the tile's tint can never leak onto artwork.
        boolean circle = config.artShape == WidgetConfig.ArtShape.CIRCLE;
        v.setViewVisibility(R.id.art_box, config.showArt ? View.VISIBLE : View.GONE);
        int artPx = px(context, s.art);
        Bitmap art = config.showArt && np.art != null
                ? Rounded.get(np, artPx, circle ? artPx / 2 : px(context, 14)) : null;
        v.setViewVisibility(R.id.art, art != null ? View.VISIBLE : View.GONE);
        v.setViewVisibility(R.id.placeholder_tile, art != null ? View.GONE : View.VISIBLE);
        v.setViewVisibility(R.id.placeholder_icon, art != null ? View.GONE : View.VISIBLE);
        if (art != null) v.setImageViewBitmap(R.id.art, art);
        v.setImageViewResource(R.id.placeholder_tile, circle ? R.drawable.circle_tile : R.drawable.rounded_square);
        v.setInt(R.id.placeholder_tile, "setColorFilter", t.placeholder);
        v.setInt(R.id.placeholder_icon, "setColorFilter", t.textSecondary);
        int iconInset = px(context, s.art * 0.28f);
        v.setViewPadding(R.id.placeholder_icon, iconInset, iconInset, iconInset, iconInset);

        // Controls.
        int ripple = t.darkForeground ? R.drawable.ripple_on_light : R.drawable.ripple_on_dark;
        for (int id : new int[]{R.id.shuffle, R.id.previous, R.id.play_pause, R.id.next}) {
            v.setInt(id, "setBackgroundResource", ripple);
        }
        v.setInt(R.id.previous, "setColorFilter", t.accent);
        v.setInt(R.id.next, "setColorFilter", t.accent);
        v.setInt(R.id.play_pause, "setColorFilter", t.accent);
        v.setViewVisibility(R.id.previous, config.showPrevious ? View.VISIBLE : View.GONE);
        v.setViewVisibility(R.id.next, config.showNext ? View.VISIBLE : View.GONE);
        int[] icons = ICONS[config.iconStyle.ordinal()];
        v.setImageViewResource(R.id.previous, icons[PREVIOUS]);
        v.setImageViewResource(R.id.next, icons[NEXT]);
        setShuffle(context, v, config.showShuffle, np.shuffle, t, icons);

        // Visualizer: the song's accent, softened so it stays an accent rather than a feature.
        int vizColor = (t.accent & 0x00FFFFFF) | VIZ_ALPHA << 24;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ColorStateList tint = ColorStateList.valueOf(vizColor);
            v.setColorStateList(R.id.viz_bars, "setIndeterminateTintList", tint);
            v.setColorStateList(R.id.viz_wave, "setIndeterminateTintList", tint);
        }
        v.setInt(R.id.viz_static, "setColorFilter", t.accent);
        v.setInt(R.id.viz_static, "setImageAlpha", VIZ_ALPHA);
        v.setImageViewResource(R.id.viz_static, config.visualizer == WidgetConfig.Visualizer.WAVE
                ? R.drawable.viz_wave_0 : R.drawable.viz_bars_0);
        setPlaying(context, v, np.playing && !idle, config.visualizer, icons);

        // A bound player that has no session can't be controlled: play opens the player (the one
        // reliable way to start it from a widget), skip/shuffle rest dimmed. Never a media key,
        // which the system could deliver to a different app.
        boolean dormant = bound && !np.hasSession;
        int controlAlpha = dormant ? DORMANT_ALPHA : 0xFF;
        v.setInt(R.id.previous, "setImageAlpha", controlAlpha);
        v.setInt(R.id.next, "setImageAlpha", controlAlpha);
        if (dormant) v.setInt(R.id.shuffle, "setImageAlpha", DORMANT_ALPHA);
        PendingIntent launchBound = dormant ? launchPlayer(context, appWidgetId, config.boundPackage) : null;
        v.setOnClickPendingIntent(R.id.shuffle, dormant ? null : control(context, appWidgetId, MediaSessions.Action.SHUFFLE));
        v.setOnClickPendingIntent(R.id.previous, dormant ? null : control(context, appWidgetId, MediaSessions.Action.PREVIOUS));
        v.setOnClickPendingIntent(R.id.play_pause, dormant ? launchBound
                : control(context, appWidgetId, MediaSessions.Action.PLAY_PAUSE));
        v.setOnClickPendingIntent(R.id.next, dormant ? null : control(context, appWidgetId, MediaSessions.Action.NEXT));

        PendingIntent open = openIntent(context, appWidgetId, config, np, hasAccess);
        v.setOnClickPendingIntent(android.R.id.background, open);
        v.setOnClickPendingIntent(R.id.art_box, open);
        v.setOnClickPendingIntent(R.id.info, open);
        return v;
    }

    /** Title view per {@link WidgetConfig.Weight} (RemoteViews can't change a typeface at runtime). */
    private static final int[] TITLE_VIEWS = {R.id.title_regular, R.id.title, R.id.title_bold};
    private static final float INFO_START_DP = 12, INFO_END_DP = 4;
    /** Vertical breathing room kept around the text block, dp. */
    private static final float TEXT_BREATHING_DP = 10;
    private static final int DORMANT_ALPHA = 0x5C;

    /** Measures a title in the system font at a weight, for {@link Typography#fitWidth}. */
    private static Typography.Measure measure(Context context, CharSequence text, WidgetConfig.Weight weight) {
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            paint.setTypeface(Typeface.create(Typeface.DEFAULT,
                    weight == WidgetConfig.Weight.BOLD ? 700 : weight == WidgetConfig.Weight.MEDIUM ? 500 : 400, false));
        } else {
            paint.setTypeface(weight == WidgetConfig.Weight.REGULAR ? Typeface.DEFAULT : Typeface.DEFAULT_BOLD);
        }
        String s = text.toString();
        return sp -> {
            paint.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp,
                    context.getResources().getDisplayMetrics()));
            return paint.measureText(s);
        };
    }

    private static final int VIZ_ALPHA = 0xB8;

    /** Glyph slots, and one complete matching set per {@link WidgetConfig.IconStyle} (in enum order). */
    private static final int PLAY = 0, PAUSE = 1, PREVIOUS = 2, NEXT = 3, SHUFFLE_OFF = 4, SHUFFLE_ON = 5;
    private static final int[][] ICONS = {
            {R.drawable.ic_play_state, R.drawable.ic_pause_state, R.drawable.ic_skip_previous_state,
                    R.drawable.ic_skip_next_state, R.drawable.ic_shuffle_off_state, R.drawable.ic_shuffle_on_state},
            {R.drawable.ic_sharp_play_state, R.drawable.ic_sharp_pause_state, R.drawable.ic_sharp_previous_state,
                    R.drawable.ic_sharp_next_state, R.drawable.ic_sharp_shuffle_off_state, R.drawable.ic_sharp_shuffle_on_state},
            {R.drawable.ic_line_play_state, R.drawable.ic_line_pause_state, R.drawable.ic_line_previous_state,
                    R.drawable.ic_line_next_state, R.drawable.ic_line_shuffle_off_state, R.drawable.ic_line_shuffle_on_state},
            {R.drawable.ic_bold_play_state, R.drawable.ic_bold_pause_state, R.drawable.ic_bold_previous_state,
                    R.drawable.ic_bold_next_state, R.drawable.ic_bold_shuffle_off_state, R.drawable.ic_bold_shuffle_on_state},
    };
    /** Widget corner radius before Android 12 (values/dimens.xml widget_radius). */
    private static final float LEGACY_RADIUS_DP = 28;
    /** Border strength over an opaque surface, and over a fully transparent one (its only outline). */
    private static final int BORDER_ALPHA_OPAQUE = 0x1C, BORDER_ALPHA_CLEAR = 0x38;

    /**
     * Play/pause glyph and the bottom visualizer.
     *
     * The animated visualizer is an indeterminate ProgressBar: the launcher runs it, it stops by
     * itself when the home screen isn't visible, and it is GONE whenever playback isn't active, so
     * nothing animates while paused. It's a still frame when system animations are off, and below
     * Android 12, where RemoteViews can't tint a ProgressBar.
     */
    static void setPlaying(Context context, RemoteViews v, boolean playing, WidgetConfig.Visualizer viz, int[] icons) {
        v.setImageViewResource(R.id.play_pause, icons[playing ? PAUSE : PLAY]);
        boolean show = playing && viz != WidgetConfig.Visualizer.OFF;
        boolean animate = show && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && animationsEnabled(context);
        v.setViewVisibility(R.id.viz_bars, animate && viz == WidgetConfig.Visualizer.BARS ? View.VISIBLE : View.GONE);
        v.setViewVisibility(R.id.viz_wave, animate && viz == WidgetConfig.Visualizer.WAVE ? View.VISIBLE : View.GONE);
        v.setViewVisibility(R.id.viz_static, show && !animate ? View.VISIBLE : View.GONE);
    }

    /**
     * On: accent plus a dot under the glyph (state isn't conveyed by color alone). Off: secondary
     * text color. Unsupported by the player: faded, and taps are ignored.
     */
    private static void setShuffle(Context context, RemoteViews v, boolean show, int mode, ColorEngine.Theme t,
                                   int[] icons) {
        v.setViewVisibility(R.id.shuffle, show ? View.VISIBLE : View.GONE);
        v.setImageViewResource(R.id.shuffle, icons[mode == Shuffle.ON ? SHUFFLE_ON : SHUFFLE_OFF]);
        v.setInt(R.id.shuffle, "setColorFilter", mode == Shuffle.ON ? t.accent : t.textSecondary);
        v.setInt(R.id.shuffle, "setImageAlpha", mode == Shuffle.UNSUPPORTED ? 0x5C : 0xFF);
        v.setContentDescription(R.id.shuffle, context.getText(mode == Shuffle.ON ? R.string.shuffle_on
                : mode == Shuffle.OFF ? R.string.shuffle_off : R.string.shuffle_unsupported));
    }

    /** Honors "Remove animations" (accessibility). Read globally: our process may have no window. */
    private static boolean animationsEnabled(Context context) {
        return Settings.Global.getFloat(context.getContentResolver(),
                Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f;
    }

    /** Portrait uses the max height, landscape the min height (AppWidget options convention). */
    public static float heightDp(Context context, AppWidgetManager manager, int appWidgetId) {
        Bundle o = manager.getAppWidgetOptions(appWidgetId);
        boolean landscape = context.getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_LANDSCAPE;
        int h = o.getInt(landscape ? AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT
                : AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0);
        return h > 0 ? h : DEFAULT_HEIGHT_DP;
    }

    /** Portrait uses the min width, landscape the max width (AppWidget options convention). */
    public static float widthDp(Context context, AppWidgetManager manager, int appWidgetId) {
        Bundle o = manager.getAppWidgetOptions(appWidgetId);
        boolean landscape = context.getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_LANDSCAPE;
        int w = o.getInt(landscape ? AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH
                : AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0);
        return w > 0 ? w : DEFAULT_WIDTH_DP;
    }

    /**
     * Placement of the optional corner shuffle. Pure, unit-tested. All values in dp.
     *
     * Visible glyph and tap target are sized separately: the glyph stays restrained (15-20dp) and
     * sits centered in the free band above the column's control glyph, while the target is a
     * generous 56dp-wide area filling that band down to just above the control's glyph. Taps on
     * the control's own glyph therefore always reach the control; the empty space above it belongs
     * to shuffle.
     */
    static final class Corner {
        static final float WIDTH = 56, MIN_HEIGHT = 28, MAX_HEIGHT = 40;
        float height, glyph, glyphTop, marginEnd;

        /**
         * @param h             widget height
         * @param lift          bottom padding of the row (row content is centered above it)
         * @param belowGlyph    glyph size of the control under the corner
         * @param columnFromEnd that control's center, measured from the widget's end edge
         */
        static Corner place(float h, float lift, float belowGlyph, float columnFromEnd) {
            Corner c = new Corner();
            float belowTop = (h - lift - belowGlyph) / 2;
            c.glyph = Sizes.clamp(belowTop - 12, 15, 20);
            c.glyphTop = Sizes.clamp((belowTop - c.glyph) / 2, 5, 10);
            c.height = Sizes.clamp(belowTop - 2, MIN_HEIGHT, MAX_HEIGHT);
            c.marginEnd = Math.max(2, columnFromEnd - WIDTH / 2);
            return c;
        }
    }

    /** Proportions for one row. Pure, unit-tested. All values in dp. */
    static final class Sizes {
        float pad, art, play, playIcon, sideTouch, sideIcon;
        /** Horizontal extent of the controls: full-height targets, a little narrower to give the title room. */
        float playWidth, sideWidth;

        static Sizes forHeight(float h) {
            Sizes s = new Sizes();
            s.pad = clamp(h * 0.15f, 8, 14);
            float inner = h - 2 * s.pad;
            s.art = clamp(inner, 36, 66);
            // Bare glyphs: play/pause leads by size alone (~1.4x prev/next) inside a generous target.
            s.play = Math.min(Math.min(56, h), Math.max(inner, 40));
            s.playIcon = Math.min(clamp(inner * 0.6f, 30, 38), s.play - 8);
            s.sideTouch = Math.min(48, Math.max(inner, 36));
            s.sideIcon = clamp(s.playIcon * 0.72f, 22, 27);
            s.playWidth = Math.min(s.play, 52);
            s.sideWidth = Math.min(s.sideTouch, 44);
            return s;
        }

        static float clamp(float v, float min, float max) {
            return Math.max(min, Math.min(max, v));
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private static void size(RemoteViews v, int id, float w, float h) {
        v.setViewLayoutWidth(id, w, TypedValue.COMPLEX_UNIT_DIP);
        v.setViewLayoutHeight(id, h, TypedValue.COMPLEX_UNIT_DIP);
    }

    private static int px(Context context, float dp) {
        return Math.round(dp * context.getResources().getDisplayMetrics().density);
    }

    private static volatile int wallpaper = 0;

    /** Wallpaper primary color, so contrast is right when the background is translucent. */
    public static int wallpaperColor(Context context) {
        int cached = wallpaper;
        if (cached != 0) return cached;
        int color = ColorEngine.UNKNOWN_WALLPAPER;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            try {
                WallpaperColors wc = WallpaperManager.getInstance(context)
                        .getWallpaperColors(WallpaperManager.FLAG_SYSTEM);
                if (wc != null) color = wc.getPrimaryColor().toArgb() | 0xFF000000;
            } catch (RuntimeException ignored) {
                // Some OEM builds restrict this; the dark default is the common case anyway.
            }
        }
        wallpaper = color;
        return color;
    }

    /** Called when the wallpaper's colors change. */
    public static void invalidateWallpaper() {
        wallpaper = 0;
    }

    /** A transport action for one widget, so the provider can route it to that widget's player. */
    private static PendingIntent control(Context context, int appWidgetId, MediaSessions.Action action) {
        Intent i = new Intent(context, RadiationWidgetProvider.class)
                .setAction(RadiationWidgetProvider.ACTION_CONTROL)
                .putExtra(RadiationWidgetProvider.EXTRA_ACTION, action.name())
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                // Distinct data so each widget and action gets its own PendingIntent.
                .setData(android.net.Uri.parse("radiation://control/" + appWidgetId + "/" + action.name()));
        return PendingIntent.getBroadcast(context, 0, i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /** Opens the bound player (or this widget's Studio page if the app is gone). */
    private static PendingIntent launchPlayer(Context context, int appWidgetId, String packageName) {
        Intent launch = context.getPackageManager().getLaunchIntentForPackage(packageName);
        return activity(context, appWidgetId, launch != null ? launch : studioIntent(context, appWidgetId));
    }

    /** Null means "tap does nothing"; RemoteViews then clears any previous click handler. */
    @Nullable
    private static PendingIntent openIntent(Context context, int appWidgetId, WidgetConfig config,
                                            NowPlaying np, boolean hasAccess) {
        if (!hasAccess) return activity(context, appWidgetId, studioIntent(context, appWidgetId));
        switch (config.tapAction) {
            case NOTHING:
                return null;
            case SPOTIFY: {
                Intent spotify = context.getPackageManager().getLaunchIntentForPackage(MediaSessions.SPOTIFY);
                if (spotify != null) return activity(context, appWidgetId, spotify);
                break; // Not installed: behave like ACTIVE_APP.
            }
            default:
                break;
        }
        if (config.boundPackage != null) {
            // Bound: the bound player, at its now-playing screen while it has a session.
            if (np.hasSession && np.sessionActivity != null) return np.sessionActivity;
            return launchPlayer(context, appWidgetId, config.boundPackage);
        }
        if (np.packageName == null) return activity(context, appWidgetId, studioIntent(context, appWidgetId));
        // The player's own intent opens it on its "now playing" screen.
        if (np.sessionActivity != null) return np.sessionActivity;
        Intent launch = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setPackage(np.packageName);
        return activity(context, appWidgetId, launch);
    }

    private static Intent studioIntent(Context context, int appWidgetId) {
        return new Intent(context, StudioActivity.class)
                .putExtra(StudioActivity.EXTRA_EDIT_WIDGET, appWidgetId);
    }

    private static PendingIntent activity(Context context, int appWidgetId, Intent intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return PendingIntent.getActivity(context, appWidgetId, intent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /** Rounded artwork at the exact pixel size shown; recomputed only when art or size changes. */
    static final class Rounded {
        private static String key;
        private static Bitmap bitmap;

        static synchronized Bitmap get(NowPlaying np, int sizePx, int radiusPx) {
            String k = np.artKey + "@" + sizePx + "/" + radiusPx;
            if (k.equals(key)) return bitmap;
            Bitmap src = np.art;
            Bitmap out = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888);
            BitmapShader shader = new BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            Matrix m = new Matrix();
            m.setScale((float) sizePx / src.getWidth(), (float) sizePx / src.getHeight());
            shader.setLocalMatrix(m);
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            paint.setShader(shader);
            new Canvas(out).drawRoundRect(0, 0, sizePx, sizePx, radiusPx, radiusPx, paint);
            key = k;
            bitmap = out;
            return out;
        }
    }
}
