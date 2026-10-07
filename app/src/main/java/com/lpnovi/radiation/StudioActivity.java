package com.lpnovi.radiation;

import android.appwidget.AppWidgetManager;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.RemoteViews;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.slider.Slider;
import com.lpnovi.radiation.config.WidgetConfig;
import com.lpnovi.radiation.config.WidgetConfig.Accent;
import com.lpnovi.radiation.config.WidgetConfig.AlbumTone;
import com.lpnovi.radiation.config.WidgetConfig.IconStyle;
import com.lpnovi.radiation.config.WidgetConfig.ArtShape;
import com.lpnovi.radiation.config.WidgetConfig.Background;
import com.lpnovi.radiation.config.WidgetConfig.TapAction;
import com.lpnovi.radiation.config.WidgetConfig.Visualizer;
import com.lpnovi.radiation.media.MediaListenerService;
import com.lpnovi.radiation.media.MediaSessions;
import com.lpnovi.radiation.widget.RadiationWidgetProvider;
import com.lpnovi.radiation.widget.WidgetRenderer;
import com.lpnovi.radiation.widget.WidgetUpdater;

import java.util.Arrays;
import java.util.function.IntConsumer;

/**
 * Widget Studio: launcher entry point and the widget's configure activity.
 *
 * State model: {@link #widgetId} names the one widget being edited (survives recreation and is
 * fixed in configure mode); {@link #config} is the source of truth for its settings. Config is
 * read from storage only when the edited widget changes, and every edit writes through to
 * storage, updates the preview in place and asks the home-screen widget to re-render.
 *
 * Every enum setting is a single-choice group whose view ids are listed in the enum's order, so
 * adding an option means adding one id here and one enum constant.
 */
public class StudioActivity extends AppCompatActivity {

    public static final String EXTRA_EDIT_WIDGET = "com.lpnovi.radiation.EDIT_WIDGET";
    private static final String STATE_WIDGET = "widget";

    // View ids in enum order.
    private static final int[] BACKGROUNDS = {R.id.bg_album_tint, R.id.bg_album_gradient, R.id.bg_amoled,
            R.id.bg_material_you, R.id.bg_glass, R.id.bg_custom};
    private static final int[] BACKGROUND_HINTS = {R.string.hint_bg_album_tint, R.string.hint_bg_album_gradient,
            R.string.hint_bg_amoled, R.string.hint_bg_material_you, R.string.hint_bg_glass, R.string.hint_bg_custom};
    private static final int[] ACCENTS = {R.id.accent_album, R.id.accent_material_you, R.id.accent_mono};
    private static final int[] VISUALIZERS = {R.id.viz_off, R.id.viz_bars, R.id.viz_wave};
    private static final int[] TONES = {R.id.tone_rich, R.id.tone_pastel};
    private static final int[] ICON_STYLES = {R.id.icons_rounded, R.id.icons_sharp, R.id.icons_line, R.id.icons_bold};
    private static final int[] ART_SHAPES = {R.id.art_rounded, R.id.art_circle};
    private static final int[] TAP_ACTIONS = {R.id.tap_active, R.id.tap_spotify, R.id.tap_nothing};

    /** Custom background swatches: deep tones that keep light text, and two light ones that flip it. */
    private static final int[] SWATCHES = {0xFF000000, 0xFF1C1B1F, 0xFF0F1B2D, 0xFF12261E, 0xFF2A1630,
            0xFF3A1A12, 0xFF2E3440, 0xFFE9E3D8, 0xFFE6EBF0};
    private static final String[] SWATCH_NAMES = {"Black", "Graphite", "Midnight", "Forest", "Plum",
            "Ember", "Slate", "Sand", "Mist"};
    /** Glass reads as glass only when translucent; picking it from fully opaque starts here. */
    private static final int GLASS_DEFAULT_PERCENT = 40;

    private int widgetId = AppWidgetManager.INVALID_APPWIDGET_ID;
    private boolean configuring;
    private WidgetConfig config = new WidgetConfig();
    /** Suppresses control listeners while controls are populated from config. */
    private boolean binding;
    /** Picking Glass lowered opacity on its own; leaving Glass may put it back. */
    private boolean glassLoweredOpacity;

    private FrameLayout preview;
    private View previewContent;
    private ChipGroup picker, backgrounds;
    private MaterialButtonToggleGroup accents, tones, iconStyles, visualizers, artShapes, tapActions;
    private Slider opacity;
    private TextView opacityValue, backgroundHint;
    private LinearLayout swatches;
    private MaterialSwitch outline, showArt, showArtist, showPrevious, showNext, showShuffle;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_studio);

        Intent intent = getIntent();
        configuring = AppWidgetManager.ACTION_APPWIDGET_CONFIGURE.equals(intent.getAction());
        if (savedInstanceState != null) {
            widgetId = savedInstanceState.getInt(STATE_WIDGET, AppWidgetManager.INVALID_APPWIDGET_ID);
        } else if (configuring) {
            widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID);
        } else {
            widgetId = intent.getIntExtra(EXTRA_EDIT_WIDGET, AppWidgetManager.INVALID_APPWIDGET_ID);
        }
        if (configuring) {
            // Backing out of first-time configuration cancels the placement, per the AppWidget contract.
            setResult(RESULT_CANCELED, resultIntent());
            View done = findViewById(R.id.done);
            done.setVisibility(View.VISIBLE);
            done.setOnClickListener(x -> {
                setResult(RESULT_OK, resultIntent());
                finish();
            });
        }

        preview = findViewById(R.id.preview);
        picker = findViewById(R.id.widget_picker);
        backgrounds = findViewById(R.id.bg_group);
        accents = findViewById(R.id.accent_group);
        visualizers = findViewById(R.id.viz_group);
        tones = findViewById(R.id.tone_group);
        iconStyles = findViewById(R.id.icon_group);
        artShapes = findViewById(R.id.art_shape_group);
        tapActions = findViewById(R.id.tap_group);
        opacity = findViewById(R.id.opacity);
        opacityValue = findViewById(R.id.opacity_value);
        backgroundHint = findViewById(R.id.bg_hint);
        swatches = findViewById(R.id.swatches);
        outline = findViewById(R.id.outline);
        showArt = findViewById(R.id.show_art);
        showArtist = findViewById(R.id.show_artist);
        showPrevious = findViewById(R.id.show_previous);
        showNext = findViewById(R.id.show_next);
        showShuffle = findViewById(R.id.show_shuffle);

        findViewById(R.id.access_grant).setOnClickListener(x -> openAccessSettings());
        View pin = findViewById(R.id.pin_widget);
        AppWidgetManager manager = AppWidgetManager.getInstance(this);
        pin.setVisibility(manager.isRequestPinAppWidgetSupported() ? View.VISIBLE : View.GONE);
        pin.setOnClickListener(x -> manager.requestPinAppWidget(
                new ComponentName(this, RadiationWidgetProvider.class), null, null));

        backgrounds.setOnCheckedStateChangeListener((g, checked) -> {
            if (checked.isEmpty()) return;
            Background picked = Background.values()[indexOf(BACKGROUNDS, checked.get(0))];
            edit(() -> {
                int glassAlpha = WidgetConfig.alphaFromPercent(GLASS_DEFAULT_PERCENT);
                if (picked == Background.GLASS && config.background != Background.GLASS
                        && config.backgroundAlpha == 255) {
                    config.backgroundAlpha = glassAlpha;
                    glassLoweredOpacity = true;
                } else if (picked != Background.GLASS && glassLoweredOpacity
                        && config.backgroundAlpha == glassAlpha) {
                    // Undo only what Glass itself changed; a user-chosen opacity stays.
                    config.backgroundAlpha = 255;
                }
                if (picked != Background.GLASS) glassLoweredOpacity = false;
                config.background = picked;
            });
        });
        onChoice(accents, ACCENTS, i -> edit(() -> config.accent = Accent.values()[i]));
        onChoice(visualizers, VISUALIZERS, i -> edit(() -> config.visualizer = Visualizer.values()[i]));
        onChoice(tones, TONES, i -> edit(() -> config.albumTone = AlbumTone.values()[i]));
        onChoice(iconStyles, ICON_STYLES, i -> edit(() -> config.iconStyle = IconStyle.values()[i]));
        onChoice(artShapes, ART_SHAPES, i -> edit(() -> config.artShape = ArtShape.values()[i]));
        onChoice(tapActions, TAP_ACTIONS, i -> edit(() -> config.tapAction = TapAction.values()[i]));
        opacity.addOnChangeListener((s, value, fromUser) -> {
            if (fromUser) glassLoweredOpacity = false; // the user owns opacity now
            edit(() -> config.backgroundAlpha = WidgetConfig.alphaFromPercent(Math.round(value)));
        });
        outline.setOnCheckedChangeListener((b, on) -> edit(() -> config.outline = on));
        showArt.setOnCheckedChangeListener((b, on) -> edit(() -> config.showArt = on));
        showArtist.setOnCheckedChangeListener((b, on) -> edit(() -> config.showArtist = on));
        showPrevious.setOnCheckedChangeListener((b, on) -> edit(() -> config.showPrevious = on));
        showNext.setOnCheckedChangeListener((b, on) -> edit(() -> config.showNext = on));
        showShuffle.setOnCheckedChangeListener((b, on) -> edit(() -> config.showShuffle = on));
        picker.setOnCheckedStateChangeListener((g, checked) -> {
            if (binding || checked.isEmpty()) return;
            select((int) g.findViewById(checked.get(0)).getTag());
        });
        buildSwatches();

        load();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Access may have just been granted in Settings; widgets may have been added or removed.
        findViewById(R.id.access_card).setVisibility(MediaSessions.hasAccess(this) ? View.GONE : View.VISIBLE);
        ((MaterialCardView) findViewById(R.id.preview_card)).setCardBackgroundColor(
                WidgetRenderer.wallpaperColor(this));
        syncWidgetList();
        WidgetUpdater.setListener(this::renderPreview);
        WidgetUpdater.request(this);
        renderPreview();
    }

    @Override
    protected void onPause() {
        WidgetUpdater.setListener(null);
        super.onPause();
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt(STATE_WIDGET, widgetId);
    }

    /** Reconciles the chip row with placed widgets; reloads config only if the edited widget is gone. */
    private void syncWidgetList() {
        int[] ids = WidgetUpdater.widgetIds(this);
        boolean present = Arrays.stream(ids).anyMatch(id -> id == widgetId);
        // A widget being configured may not be listed yet on some launchers; never swap it out.
        if (!present && !configuring) {
            select(ids.length > 0 ? ids[0] : AppWidgetManager.INVALID_APPWIDGET_ID);
        }
        boolean editable = widgetId != AppWidgetManager.INVALID_APPWIDGET_ID;
        findViewById(R.id.no_widgets).setVisibility(editable ? View.GONE : View.VISIBLE);
        findViewById(R.id.editor).setVisibility(editable ? View.VISIBLE : View.GONE);
        findViewById(R.id.widget_picker_scroll).setVisibility(ids.length > 1 ? View.VISIBLE : View.GONE);

        binding = true;
        picker.removeAllViews();
        for (int i = 0; i < ids.length; i++) {
            Chip chip = new Chip(this);
            chip.setText(getString(R.string.widget_n, i + 1));
            chip.setCheckable(true);
            chip.setId(View.generateViewId());
            chip.setTag(ids[i]);
            picker.addView(chip);
            if (ids[i] == widgetId) picker.check(chip.getId());
        }
        binding = false;
    }

    private void select(int id) {
        if (id == widgetId) return;
        widgetId = id;
        load();
    }

    /** The only place config is read from storage. */
    private void load() {
        config = WidgetConfig.load(this, widgetId);
        glassLoweredOpacity = false;
        binding = true;
        backgrounds.check(BACKGROUNDS[config.background.ordinal()]);
        accents.check(ACCENTS[config.accent.ordinal()]);
        visualizers.check(VISUALIZERS[config.visualizer.ordinal()]);
        tones.check(TONES[config.albumTone.ordinal()]);
        iconStyles.check(ICON_STYLES[config.iconStyle.ordinal()]);
        artShapes.check(ART_SHAPES[config.artShape.ordinal()]);
        tapActions.check(TAP_ACTIONS[config.tapAction.ordinal()]);
        outline.setChecked(config.outline);
        showArt.setChecked(config.showArt);
        showArtist.setChecked(config.showArtist);
        showPrevious.setChecked(config.showPrevious);
        showNext.setChecked(config.showNext);
        showShuffle.setChecked(config.showShuffle);
        binding = false;
        refreshDependentControls();
        renderPreview();
    }

    private void edit(Runnable change) {
        if (binding || widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return;
        change.run();
        config.save(this, widgetId);
        refreshDependentControls();
        renderPreview();
        WidgetUpdater.request(this); // throttled; slider drags collapse into a few renders
    }

    /** Controls whose content or availability follows other settings. */
    private void refreshDependentControls() {
        backgroundHint.setText(BACKGROUND_HINTS[config.background.ordinal()]);
        findViewById(R.id.swatch_scroll).setVisibility(
                config.background == Background.CUSTOM ? View.VISIBLE : View.GONE);
        for (int i = 0; i < swatches.getChildCount(); i++) {
            styleSwatch(swatches.getChildAt(i), SWATCHES[i], SWATCHES[i] == config.customColor);
        }
        int percent = WidgetConfig.percentFromAlpha(config.backgroundAlpha);
        if (Math.round(opacity.getValue()) != percent) {
            binding = true;
            opacity.setValue(percent);
            binding = false;
        }
        opacityValue.setText(getString(R.string.opacity_percent, percent));
        // Glass always has its hairline edge, so the separate outline switch would do nothing.
        outline.setEnabled(config.background != Background.GLASS);
        for (int i = 0; i < artShapes.getChildCount(); i++) artShapes.getChildAt(i).setEnabled(config.showArt);
    }

    private void buildSwatches() {
        int size = Math.round(40 * getResources().getDisplayMetrics().density);
        int gap = Math.round(10 * getResources().getDisplayMetrics().density);
        for (int i = 0; i < SWATCHES.length; i++) {
            int color = SWATCHES[i];
            View swatch = new View(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
            lp.setMarginEnd(gap);
            swatch.setLayoutParams(lp);
            swatch.setContentDescription(SWATCH_NAMES[i]);
            swatch.setOnClickListener(x -> edit(() -> config.customColor = color));
            swatches.addView(swatch);
        }
    }

    private void styleSwatch(View swatch, int color, boolean selected) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color);
        float density = getResources().getDisplayMetrics().density;
        d.setStroke(Math.round((selected ? 3 : 1) * density), selected
                ? MaterialColors.getColor(swatch, androidx.appcompat.R.attr.colorPrimary)
                : MaterialColors.getColor(swatch, com.google.android.material.R.attr.colorOutlineVariant));
        swatch.setBackground(d);
        swatch.setSelected(selected);
    }

    private void onChoice(MaterialButtonToggleGroup group, int[] ids, IntConsumer onPick) {
        group.addOnButtonCheckedListener((g, id, checked) -> {
            if (checked) onPick.accept(indexOf(ids, id));
        });
    }

    private static int indexOf(int[] ids, int id) {
        for (int i = 0; i < ids.length; i++) if (ids[i] == id) return i;
        return 0;
    }

    /**
     * The preview is the real widget: same RemoteViews, inflated locally at the real cell height,
     * and updated in place (reapply) so edits don't flicker.
     */
    private void renderPreview() {
        if (preview == null) return;
        Context app = getApplicationContext(); // AppCompat's inflater would swap in views RemoteViews rejects
        AppWidgetManager manager = AppWidgetManager.getInstance(this);
        boolean placed = widgetId != AppWidgetManager.INVALID_APPWIDGET_ID;
        float widthDp = placed ? WidgetRenderer.widthDp(this, manager, widgetId) : 320;
        float heightDp = !placed
                ? 80 : WidgetRenderer.heightDp(this, manager, widgetId);
        RemoteViews views = WidgetRenderer.build(app, widgetId, config, WidgetUpdater.latest(),
                MediaSessions.hasAccess(this), widthDp, heightDp);
        ViewGroup.LayoutParams lp = preview.getLayoutParams();
        int heightPx = Math.round(heightDp * getResources().getDisplayMetrics().density);
        if (lp.height != heightPx) {
            lp.height = heightPx;
            preview.setLayoutParams(lp);
        }
        if (previewContent == null) {
            previewContent = views.apply(app, preview);
            preview.addView(previewContent);
        } else {
            views.reapply(app, previewContent);
        }
    }

    private void openAccessSettings() {
        Intent detail = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                ? new Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                        .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                                new ComponentName(this, MediaListenerService.class).flattenToString())
                : new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
        try {
            startActivity(detail);
        } catch (ActivityNotFoundException e) {
            startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
        }
    }

    private Intent resultIntent() {
        return new Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId);
    }
}
