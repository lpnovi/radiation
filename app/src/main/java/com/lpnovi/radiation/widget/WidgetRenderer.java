package com.lpnovi.radiation.widget;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Shader;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.text.TextUtils;
import android.view.View;
import android.widget.RemoteViews;

import androidx.annotation.Nullable;

import com.lpnovi.radiation.R;
import com.lpnovi.radiation.StudioActivity;
import com.lpnovi.radiation.config.WidgetConfig;
import com.lpnovi.radiation.media.MediaSessions;

/** Turns (current media session, per-widget config) into RemoteViews. Shared by the widget and the studio preview. */
public final class WidgetRenderer {

    private WidgetRenderer() {}

    public static void updateAll(Context context) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        int[] ids = manager.getAppWidgetIds(new ComponentName(context, RadiationWidgetProvider.class));
        if (ids.length == 0) return;
        MediaController controller = MediaSessions.active(context);
        boolean access = MediaSessions.hasAccess(context);
        for (int id : ids) {
            manager.updateAppWidget(id, build(context, id, WidgetConfig.load(context, id), controller, access));
        }
    }

    public static RemoteViews build(Context context, int appWidgetId, WidgetConfig config,
                                    @Nullable MediaController controller, boolean hasAccess) {
        RemoteViews v = new RemoteViews(context.getPackageName(), R.layout.widget_radiation);
        Palette p = Palette.of(context, config);

        v.setInt(R.id.background, "setColorFilter", p.background);
        v.setInt(R.id.background, "setImageAlpha", config.backgroundAlpha);
        v.setTextColor(R.id.title, p.text);
        v.setTextColor(R.id.artist, p.textSecondary);
        v.setInt(R.id.previous, "setColorFilter", p.control);
        v.setInt(R.id.next, "setColorFilter", p.control);
        v.setInt(R.id.play_pause_bg, "setColorFilter", p.control);
        v.setInt(R.id.play_pause_icon, "setColorFilter", p.background);
        v.setViewVisibility(R.id.art, config.showArt ? View.VISIBLE : View.GONE);
        v.setViewVisibility(R.id.previous, config.showPrevious ? View.VISIBLE : View.GONE);
        v.setViewVisibility(R.id.next, config.showNext ? View.VISIBLE : View.GONE);

        MediaMetadata meta = controller == null ? null : controller.getMetadata();
        CharSequence title = meta == null ? null : meta.getText(MediaMetadata.METADATA_KEY_TITLE);
        CharSequence artist = meta == null ? null : firstNonEmpty(
                meta.getText(MediaMetadata.METADATA_KEY_ARTIST),
                meta.getText(MediaMetadata.METADATA_KEY_ALBUM_ARTIST));
        if (TextUtils.isEmpty(title)) {
            v.setTextViewText(R.id.title, context.getText(R.string.nothing_playing));
            v.setTextViewText(R.id.artist, context.getText(hasAccess ? R.string.app_name : R.string.tap_to_connect));
        } else {
            v.setTextViewText(R.id.title, title);
            v.setTextViewText(R.id.artist, artist == null ? "" : artist);
        }

        Bitmap art = config.showArt && meta != null ? Artwork.get(context, meta) : null;
        if (art != null) {
            v.setImageViewBitmap(R.id.art, art);
            v.setViewPadding(R.id.art, 0, 0, 0, 0);
        } else {
            v.setImageViewResource(R.id.art, R.drawable.ic_music_note);
            int pad = Math.round(11 * context.getResources().getDisplayMetrics().density);
            v.setViewPadding(R.id.art, pad, pad, pad, pad);
            v.setInt(R.id.art, "setColorFilter", p.textSecondary);
        }

        boolean playing = MediaSessions.isPlaying(controller);
        v.setImageViewResource(R.id.play_pause_icon, playing ? R.drawable.ic_pause : R.drawable.ic_play);

        v.setOnClickPendingIntent(R.id.previous, control(context, MediaSessions.Action.PREVIOUS));
        v.setOnClickPendingIntent(R.id.play_pause, control(context, MediaSessions.Action.PLAY_PAUSE));
        v.setOnClickPendingIntent(R.id.next, control(context, MediaSessions.Action.NEXT));

        PendingIntent open = openIntent(context, appWidgetId, config, controller, hasAccess);
        if (open != null) {
            v.setOnClickPendingIntent(R.id.root, open);
            v.setOnClickPendingIntent(R.id.art, open);
            v.setOnClickPendingIntent(R.id.info, open);
        }
        return v;
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

    @Nullable
    private static PendingIntent openIntent(Context context, int appWidgetId, WidgetConfig config,
                                            @Nullable MediaController controller, boolean hasAccess) {
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
        if (controller == null) return activity(context, appWidgetId, studioIntent(context, appWidgetId));
        // The player's own intent opens it on its "now playing" screen.
        PendingIntent session = controller.getSessionActivity();
        if (session != null) return session;
        Intent launch = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setPackage(controller.getPackageName());
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

    @Nullable
    private static CharSequence firstNonEmpty(CharSequence a, CharSequence b) {
        return TextUtils.isEmpty(a) ? b : a;
    }

    /** Resolved colors for one render. The color engine (album-art-derived palettes) will plug in here. */
    static final class Palette {
        int background, text, textSecondary, control;

        static Palette of(Context context, WidgetConfig config) {
            Palette p = new Palette();
            if (config.style == WidgetConfig.Style.MATERIAL_YOU) {
                // ponytail: dynamic colors are resolved at render time; the listener service re-renders on config change.
                p.background = context.getColor(R.color.my_bg);
                p.text = context.getColor(R.color.my_text);
                p.textSecondary = context.getColor(R.color.my_text_secondary);
                p.control = context.getColor(R.color.my_control);
            } else {
                p.background = Color.BLACK;
                p.text = Color.WHITE;
                p.textSecondary = 0x99FFFFFF;
                p.control = Color.WHITE;
            }
            return p;
        }
    }

    /** Scales and rounds album art once per track; RemoteViews bitmaps must stay small. */
    static final class Artwork {
        private static String cachedKey;
        private static Bitmap cached;

        @Nullable
        static synchronized Bitmap get(Context context, MediaMetadata meta) {
            String key = meta.getString(MediaMetadata.METADATA_KEY_TITLE) + '\u0000'
                    + meta.getString(MediaMetadata.METADATA_KEY_ARTIST) + '\u0000'
                    + meta.getString(MediaMetadata.METADATA_KEY_ALBUM);
            if (key.equals(cachedKey)) return cached;
            Bitmap src = meta.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
            if (src == null) src = meta.getBitmap(MediaMetadata.METADATA_KEY_ART);
            if (src == null) src = meta.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
            cachedKey = key;
            cached = src == null ? null : rounded(src,
                    context.getResources().getDimensionPixelSize(R.dimen.art_size),
                    context.getResources().getDimension(R.dimen.art_radius));
            return cached;
        }

        /** Center-crops src to a size×size square with rounded corners. */
        private static Bitmap rounded(Bitmap src, int size, float radius) {
            Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            BitmapShader shader = new BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            float scale = (float) size / Math.min(src.getWidth(), src.getHeight());
            Matrix m = new Matrix();
            m.setScale(scale, scale);
            m.postTranslate((size - src.getWidth() * scale) / 2f, (size - src.getHeight() * scale) / 2f);
            shader.setLocalMatrix(m);
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            paint.setShader(shader);
            new Canvas(out).drawRoundRect(0, 0, size, size, radius, radius, paint);
            return out;
        }
    }
}
