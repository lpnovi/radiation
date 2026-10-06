package com.lpnovi.radiation;

import android.appwidget.AppWidgetManager;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.RemoteViews;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.slider.Slider;
import com.lpnovi.radiation.config.WidgetConfig;
import com.lpnovi.radiation.media.MediaListenerService;
import com.lpnovi.radiation.media.MediaSessions;
import com.lpnovi.radiation.widget.RadiationWidgetProvider;
import com.lpnovi.radiation.widget.WidgetRenderer;
import com.lpnovi.radiation.widget.WidgetUpdater;

import java.util.Arrays;

/**
 * Widget Studio: launcher entry point and the widget's configure activity.
 *
 * State model: {@link #widgetId} names the one widget being edited (survives recreation and is
 * fixed in configure mode); {@link #config} is the source of truth for its settings. Config is
 * read from storage only when the edited widget changes, and every edit writes through to
 * storage, updates the preview in place and asks the home-screen widget to re-render.
 */
public class StudioActivity extends AppCompatActivity {

    public static final String EXTRA_EDIT_WIDGET = "com.lpnovi.radiation.EDIT_WIDGET";
    private static final String STATE_WIDGET = "widget";

    private int widgetId = AppWidgetManager.INVALID_APPWIDGET_ID;
    private boolean configuring;
    private WidgetConfig config = new WidgetConfig();
    /** Suppresses control listeners while controls are populated from config. */
    private boolean binding;

    private FrameLayout preview;
    private View previewContent;
    private MaterialButtonToggleGroup styleGroup, tapGroup;
    private Slider opacity;
    private TextView opacityValue, styleHint;
    private MaterialSwitch showArt, showPrevious, showNext;
    private ChipGroup picker;

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
        styleGroup = findViewById(R.id.style_group);
        tapGroup = findViewById(R.id.tap_group);
        opacity = findViewById(R.id.opacity);
        opacityValue = findViewById(R.id.opacity_value);
        styleHint = findViewById(R.id.style_hint);
        showArt = findViewById(R.id.show_art);
        showPrevious = findViewById(R.id.show_previous);
        showNext = findViewById(R.id.show_next);

        findViewById(R.id.access_grant).setOnClickListener(x -> openAccessSettings());
        View pin = findViewById(R.id.pin_widget);
        AppWidgetManager manager = AppWidgetManager.getInstance(this);
        pin.setVisibility(manager.isRequestPinAppWidgetSupported() ? View.VISIBLE : View.GONE);
        pin.setOnClickListener(x -> manager.requestPinAppWidget(
                new ComponentName(this, RadiationWidgetProvider.class), null, null));

        styleGroup.addOnButtonCheckedListener((g, id, checked) -> {
            if (!checked) return;
            edit(() -> config.style = id == R.id.style_material_you ? WidgetConfig.Style.MATERIAL_YOU
                    : id == R.id.style_amoled ? WidgetConfig.Style.AMOLED : WidgetConfig.Style.ALBUM);
        });
        tapGroup.addOnButtonCheckedListener((g, id, checked) -> {
            if (!checked) return;
            edit(() -> config.tapAction = id == R.id.tap_spotify ? WidgetConfig.TapAction.SPOTIFY
                    : id == R.id.tap_nothing ? WidgetConfig.TapAction.NOTHING
                    : WidgetConfig.TapAction.ACTIVE_APP);
        });
        opacity.addOnChangeListener((s, value, fromUser) -> {
            opacityValue.setText(getString(R.string.opacity_percent, Math.round(value)));
            edit(() -> config.backgroundAlpha = WidgetConfig.alphaFromPercent(Math.round(value)));
        });
        showArt.setOnCheckedChangeListener((b, on) -> edit(() -> config.showArt = on));
        showPrevious.setOnCheckedChangeListener((b, on) -> edit(() -> config.showPrevious = on));
        showNext.setOnCheckedChangeListener((b, on) -> edit(() -> config.showNext = on));
        picker.setOnCheckedStateChangeListener((g, checked) -> {
            if (binding || checked.isEmpty()) return;
            select((int) g.findViewById(checked.get(0)).getTag());
        });

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
        binding = true;
        styleGroup.check(config.style == WidgetConfig.Style.MATERIAL_YOU ? R.id.style_material_you
                : config.style == WidgetConfig.Style.AMOLED ? R.id.style_amoled : R.id.style_album);
        tapGroup.check(config.tapAction == WidgetConfig.TapAction.SPOTIFY ? R.id.tap_spotify
                : config.tapAction == WidgetConfig.TapAction.NOTHING ? R.id.tap_nothing : R.id.tap_active);
        int percent = WidgetConfig.percentFromAlpha(config.backgroundAlpha);
        opacity.setValue(percent);
        opacityValue.setText(getString(R.string.opacity_percent, percent));
        showArt.setChecked(config.showArt);
        showPrevious.setChecked(config.showPrevious);
        showNext.setChecked(config.showNext);
        binding = false;
        updateStyleHint();
        renderPreview();
    }

    private void edit(Runnable change) {
        if (binding || widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return;
        change.run();
        config.save(this, widgetId);
        updateStyleHint();
        renderPreview();
        WidgetUpdater.request(this); // throttled; slider drags collapse into a few renders
    }

    private void updateStyleHint() {
        styleHint.setText(config.style == WidgetConfig.Style.MATERIAL_YOU ? R.string.hint_material_you
                : config.style == WidgetConfig.Style.AMOLED ? R.string.hint_amoled : R.string.hint_album);
    }

    /**
     * The preview is the real widget: same RemoteViews, inflated locally at the real cell height,
     * and updated in place (reapply) so edits don't flicker.
     */
    private void renderPreview() {
        if (preview == null) return;
        Context app = getApplicationContext(); // AppCompat's inflater would swap in views RemoteViews rejects
        AppWidgetManager manager = AppWidgetManager.getInstance(this);
        float heightDp = widgetId == AppWidgetManager.INVALID_APPWIDGET_ID
                ? 80 : WidgetRenderer.heightDp(this, manager, widgetId);
        RemoteViews views = WidgetRenderer.build(app, widgetId, config, WidgetUpdater.latest(),
                MediaSessions.hasAccess(this), heightDp);
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
