package com.lpnovi.radiation.widget;

import android.app.PendingIntent;
import android.app.WallpaperColors;
import android.app.WallpaperManager;
import android.appwidget.AppWidgetManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Shader;
import android.os.Build;
import android.os.Bundle;
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

/**
 * (NowPlaying, per-widget config, cell height) -> RemoteViews. Shared by the home-screen widget
 * and the studio preview. Sets every dynamic property on every call, because hosts may reapply
 * an update onto the previous view tree.
 */
public final class WidgetRenderer {

    static final float DEFAULT_HEIGHT_DP = 80;

    private WidgetRenderer() {}

    public static RemoteViews build(Context context, int appWidgetId, WidgetConfig config,
                                    NowPlaying np, boolean hasAccess, float heightDp) {
        RemoteViews v = new RemoteViews(context.getPackageName(), R.layout.widget_radiation);
        ColorEngine.Theme t = ColorEngine.theme(config.style, config.backgroundAlpha, np.seed,
                wallpaperColor(context), context.getColor(R.color.my_bg), context.getColor(R.color.my_control));
        Sizes s = Sizes.forHeight(heightDp);

        // Surface: the only thing background opacity affects.
        v.setInt(R.id.surface, "setColorFilter", t.surface);
        v.setInt(R.id.surface, "setImageAlpha", config.backgroundAlpha);

        // Proportions from the real cell height (Android 12+; older launchers use the XML defaults).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            size(v, R.id.art_box, s.art, s.art);
            size(v, R.id.play_pause, s.play, s.play);
            size(v, R.id.previous, s.sideTouch, s.sideTouch);
            size(v, R.id.next, s.sideTouch, s.sideTouch);
        }
        int pad = px(context, s.pad);
        v.setViewPadding(R.id.row, pad, 0, px(context, Math.max(4, s.pad - 6)), 0);
        int sideInset = px(context, (s.sideTouch - s.sideIcon) / 2f);
        v.setViewPadding(R.id.previous, sideInset, sideInset, sideInset, sideInset);
        v.setViewPadding(R.id.next, sideInset, sideInset, sideInset, sideInset);
        int playInset = px(context, (s.play - s.playIcon) / 2f);
        v.setViewPadding(R.id.play_pause_icon, playInset, playInset, playInset, playInset);

        // Text.
        boolean nothing = TextUtils.isEmpty(np.title);
        v.setTextViewText(R.id.title, nothing ? context.getText(R.string.nothing_playing) : np.title);
        v.setTextViewText(R.id.artist, nothing
                ? context.getText(hasAccess ? R.string.app_name : R.string.tap_to_connect)
                : np.artist == null ? "" : np.artist);
        v.setTextColor(R.id.title, t.text);
        v.setTextColor(R.id.artist, t.textSecondary);

        // Artwork vs fallback tile: separate views, so the tile's tint can never leak onto artwork.
        v.setViewVisibility(R.id.art_box, config.showArt ? View.VISIBLE : View.GONE);
        Bitmap art = config.showArt && np.art != null
                ? Rounded.get(np, px(context, s.art), px(context, 14)) : null;
        v.setViewVisibility(R.id.art, art != null ? View.VISIBLE : View.GONE);
        v.setViewVisibility(R.id.placeholder_tile, art != null ? View.GONE : View.VISIBLE);
        v.setViewVisibility(R.id.placeholder_icon, art != null ? View.GONE : View.VISIBLE);
        if (art != null) v.setImageViewBitmap(R.id.art, art);
        v.setInt(R.id.placeholder_tile, "setColorFilter", t.placeholder);
        v.setInt(R.id.placeholder_icon, "setColorFilter", t.textSecondary);
        int iconInset = px(context, s.art * 0.28f);
        v.setViewPadding(R.id.placeholder_icon, iconInset, iconInset, iconInset, iconInset);

        // Controls.
        int ripple = t.darkForeground ? R.drawable.ripple_on_light : R.drawable.ripple_on_dark;
        v.setInt(R.id.previous, "setBackgroundResource", ripple);
        v.setInt(R.id.next, "setBackgroundResource", ripple);
        v.setInt(R.id.previous, "setColorFilter", t.accent);
        v.setInt(R.id.next, "setColorFilter", t.accent);
        v.setInt(R.id.play_pause_disc, "setColorFilter", t.accent);
        v.setInt(R.id.play_pause_icon, "setColorFilter", t.onAccent);
        v.setViewVisibility(R.id.previous, config.showPrevious ? View.VISIBLE : View.GONE);
        v.setViewVisibility(R.id.next, config.showNext ? View.VISIBLE : View.GONE);
        setPlayGlyph(v, np.playing);

        v.setOnClickPendingIntent(R.id.previous, control(context, MediaSessions.Action.PREVIOUS));
        v.setOnClickPendingIntent(R.id.play_pause, control(context, MediaSessions.Action.PLAY_PAUSE));
        v.setOnClickPendingIntent(R.id.next, control(context, MediaSessions.Action.NEXT));

        PendingIntent open = openIntent(context, appWidgetId, config, np, hasAccess);
        v.setOnClickPendingIntent(android.R.id.background, open);
        v.setOnClickPendingIntent(R.id.art_box, open);
        v.setOnClickPendingIntent(R.id.info, open);
        return v;
    }

    static void setPlayGlyph(RemoteViews v, boolean playing) {
        v.setImageViewResource(R.id.play_pause_icon, playing ? R.drawable.ic_pause_state : R.drawable.ic_play_state);
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

    /** Proportions for one row. Pure, unit-tested. All values in dp. */
    static final class Sizes {
        float pad, art, play, playIcon, sideTouch, sideIcon;

        static Sizes forHeight(float h) {
            Sizes s = new Sizes();
            s.pad = clamp(h * 0.15f, 8, 14);
            float inner = h - 2 * s.pad;
            s.art = clamp(inner, 36, 66);
            // Focal point: as large as the row allows, at least a 44dp target, at most 58dp.
            s.play = Math.min(clamp(inner * 0.88f, 44, 58), Math.max(inner, 36));
            s.playIcon = s.play * 0.46f;
            s.sideTouch = Math.min(48, Math.max(inner, 36));
            s.sideIcon = clamp(s.play * 0.5f, 22, 30);
            return s;
        }

        private static float clamp(float v, float min, float max) {
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

    private static PendingIntent control(Context context, MediaSessions.Action action) {
        Intent i = new Intent(context, RadiationWidgetProvider.class)
                .setAction(RadiationWidgetProvider.ACTION_CONTROL)
                .putExtra(RadiationWidgetProvider.EXTRA_ACTION, action.name())
                // Distinct data so each action gets its own PendingIntent.
                .setData(android.net.Uri.parse("radiation://control/" + action.name()));
        return PendingIntent.getBroadcast(context, 0, i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
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
