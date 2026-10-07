package com.lpnovi.radiation;

import android.appwidget.AppWidgetManager;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RemoteViews;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
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
import com.lpnovi.radiation.config.WidgetConfig.Weight;
import com.lpnovi.radiation.media.MediaListenerService;
import com.lpnovi.radiation.media.MediaSessions;
import com.lpnovi.radiation.media.NowPlaying;
import com.lpnovi.radiation.widget.Players;
import com.lpnovi.radiation.widget.RadiationWidgetProvider;
import com.lpnovi.radiation.widget.WidgetRenderer;
import com.lpnovi.radiation.widget.WidgetUpdater;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
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
    private static final int[] VISUALIZERS = {R.id.choice_viz_off, R.id.choice_viz_bars, R.id.choice_viz_wave};
    private static final int[] TONES = {R.id.tone_rich, R.id.tone_pastel};
    private static final int[] ICON_STYLES = {R.id.icons_rounded, R.id.icons_sharp, R.id.icons_line, R.id.icons_bold};
    private static final int[] ART_SHAPES = {R.id.art_rounded, R.id.art_circle};
    private static final int[] TAP_ACTIONS = {R.id.tap_active, R.id.tap_spotify, R.id.tap_nothing};
    private static final int[] TITLE_WEIGHTS = {R.id.ty_title_regular, R.id.ty_title_medium, R.id.ty_title_bold};
    private static final int[] ARTIST_WEIGHTS = {R.id.ty_artist_regular, R.id.ty_artist_medium};
    private static final int[] TITLE_LINES = {R.id.lines_one, R.id.lines_two};

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
    private MaterialButtonToggleGroup titleWeights, artistWeights, titleLines;
    private Slider opacity, titleSize, artistSize;
    private TextView opacityValue, backgroundHint, titleSizeValue, artistSizeValue, bindValue;
    private ImageView bindIcon;
    private LinearLayout swatches;
    private MaterialSwitch outline, showArt, showArtist, showPrevious, showNext, showShuffle;
    private MaterialSwitch showTitle, fitTitle, previewSample;
    /** Studio-only: show long sample metadata in the preview instead of what's playing. */
    private boolean useSample;
    private NowPlaying sample;

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
        titleWeights = findViewById(R.id.title_weight_group);
        artistWeights = findViewById(R.id.artist_weight_group);
        titleLines = findViewById(R.id.title_lines_group);
        titleSize = findViewById(R.id.title_size);
        artistSize = findViewById(R.id.artist_size);
        titleSizeValue = findViewById(R.id.title_size_value);
        artistSizeValue = findViewById(R.id.artist_size_value);
        showTitle = findViewById(R.id.show_title);
        fitTitle = findViewById(R.id.fit_title);
        previewSample = findViewById(R.id.preview_sample);
        bindValue = findViewById(R.id.bind_value);
        bindIcon = findViewById(R.id.bind_icon);

        findViewById(R.id.bind_row).setOnClickListener(x -> showPlayerPicker());
        onChoice(titleWeights, TITLE_WEIGHTS, i -> edit(() -> config.titleWeight = Weight.values()[i]));
        onChoice(artistWeights, ARTIST_WEIGHTS, i -> edit(() -> config.artistWeight = Weight.values()[i]));
        onChoice(titleLines, TITLE_LINES, i -> edit(() -> config.titleLines = i + 1));
        titleSize.addOnChangeListener((s, value, fromUser) -> edit(() -> config.titleSize = value));
        artistSize.addOnChangeListener((s, value, fromUser) -> edit(() -> config.artistSize = value));
        showTitle.setOnCheckedChangeListener((b, on) -> edit(() -> config.showTitle = on));
        fitTitle.setOnCheckedChangeListener((b, on) -> edit(() -> config.fitTitle = on));
        previewSample.setOnCheckedChangeListener((b, on) -> {
            useSample = on;
            renderPreview();
        });

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
        showTitle.setChecked(config.showTitle);
        fitTitle.setChecked(config.fitTitle);
        titleWeights.check(TITLE_WEIGHTS[config.titleWeight.ordinal()]);
        artistWeights.check(ARTIST_WEIGHTS[Math.min(1, config.artistWeight.ordinal())]);
        titleLines.check(TITLE_LINES[config.titleLines - 1]);
        titleSize.setValue(snap(config.titleSize, titleSize));
        artistSize.setValue(snap(config.artistSize, artistSize));
        // Long sample text by default when nothing is playing for this widget, so typography is judgeable.
        useSample = TextUtils.isEmpty(WidgetUpdater.latest(config.boundPackage).title);
        previewSample.setChecked(useSample);
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
        titleSizeValue.setText(getString(R.string.size_sp, config.titleSize));
        artistSizeValue.setText(getString(R.string.size_sp, config.artistSize));
        findViewById(R.id.title_options).setVisibility(config.showTitle ? View.VISIBLE : View.GONE);
        findViewById(R.id.artist_options).setVisibility(config.showArtist ? View.VISIBLE : View.GONE);
        refreshBindRow();
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

    // --- Bind to player ---

    /** The bound player's icon and name, or "Follow active player". */
    private void refreshBindRow() {
        if (config.boundPackage == null) {
            bindValue.setText(R.string.follow_active);
            bindIcon.setImageResource(R.drawable.ic_music_note);
            bindIcon.setImageTintList(ColorStateList.valueOf(
                    MaterialColors.getColor(bindIcon, com.google.android.material.R.attr.colorOnSurfaceVariant)));
            return;
        }
        CharSequence label = Players.label(this, config.boundPackage, config.boundLabel);
        boolean installed = Players.isInstalled(this, config.boundPackage);
        bindValue.setText(installed ? label : getString(R.string.player_unavailable, label));
        try {
            bindIcon.setImageTintList(null);
            bindIcon.setImageDrawable(getPackageManager().getApplicationIcon(config.boundPackage));
        } catch (PackageManager.NameNotFoundException e) {
            bindIcon.setImageResource(R.drawable.ic_music_note);
        }
    }

    /** Lists players (found off the main thread) in a dialog; picking one binds this widget to it. */
    private void showPlayerPicker() {
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return;
        AlertDialog loading = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.picker_title)
                .setMessage(R.string.picker_loading)
                .show();
        Context app = getApplicationContext();
        new Thread(() -> {
            List<Players.Player> found = Players.find(app);
            runOnUiThread(() -> {
                loading.dismiss();
                if (!isFinishing()) showPlayerList(found);
            });
        }, "radiation-players").start();
    }

    private void showPlayerList(List<Players.Player> found) {
        // Entries: "Follow active player", every player found, and a bound app that has since vanished.
        List<String> packages = new ArrayList<>();
        List<CharSequence> names = new ArrayList<>();
        List<Drawable> icons = new ArrayList<>();
        List<CharSequence> statuses = new ArrayList<>();
        packages.add(null);
        names.add(getString(R.string.follow_active));
        icons.add(null);
        statuses.add(null);
        boolean boundListed = config.boundPackage == null;
        for (Players.Player p : found) {
            packages.add(p.packageName);
            names.add(p.label);
            icons.add(p.icon);
            statuses.add(p.hasSession ? getString(R.string.playing_now) : null);
            boundListed |= p.packageName.equals(config.boundPackage);
        }
        if (!boundListed) {
            packages.add(config.boundPackage);
            names.add(Players.label(this, config.boundPackage, config.boundLabel));
            icons.add(null);
            statuses.add(getString(R.string.player_not_installed));
        }

        LayoutInflater inflater = LayoutInflater.from(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this, R.layout.item_player, packages) {
            @NonNull
            @Override
            public View getView(int position, View row, @NonNull ViewGroup parent) {
                if (row == null) row = inflater.inflate(R.layout.item_player, parent, false);
                ImageView icon = row.findViewById(R.id.player_icon);
                if (icons.get(position) != null) {
                    icon.setImageTintList(null);
                    icon.setImageDrawable(icons.get(position));
                } else {
                    icon.setImageResource(R.drawable.ic_music_note);
                    icon.setImageTintList(ColorStateList.valueOf(MaterialColors.getColor(icon,
                            com.google.android.material.R.attr.colorOnSurfaceVariant)));
                }
                ((TextView) row.findViewById(R.id.player_name)).setText(names.get(position));
                TextView status = row.findViewById(R.id.player_status);
                status.setVisibility(statuses.get(position) == null ? View.GONE : View.VISIBLE);
                status.setText(statuses.get(position));
                boolean selected = java.util.Objects.equals(packages.get(position), config.boundPackage);
                row.findViewById(R.id.player_check).setVisibility(selected ? View.VISIBLE : View.INVISIBLE);
                return row;
            }
        };
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.picker_title)
                .setAdapter(adapter, (d, which) -> edit(() -> {
                    config.boundPackage = packages.get(which);
                    config.boundLabel = which == 0 ? null : names.get(which).toString();
                }))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** Snaps a stored value onto a slider's range and step (stored values may predate the slider). */
    private static float snap(float value, Slider slider) {
        float stepped = Math.round(value / slider.getStepSize()) * slider.getStepSize();
        return Math.max(slider.getValueFrom(), Math.min(slider.getValueTo(), stepped));
    }

    /** Long realistic metadata with sample artwork, for judging typography. */
    private NowPlaying sample() {
        if (sample == null) {
            Bitmap art = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888);
            Paint paint = new Paint();
            paint.setShader(new LinearGradient(0, 0, 256, 256, 0xFFC0504D, 0xFF2D4A7A, Shader.TileMode.CLAMP));
            new Canvas(art).drawRect(0, 0, 256, 256, paint);
            sample = NowPlaying.sample(getString(R.string.sample_title), getString(R.string.sample_artist),
                    art, 0xFFB5463F);
        }
        return sample;
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
        NowPlaying np = useSample ? sample() : WidgetUpdater.latest(config.boundPackage);
        RemoteViews views = WidgetRenderer.build(app, widgetId, config, np,
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
