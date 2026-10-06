package com.lpnovi.radiation;

import android.appwidget.AppWidgetManager;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.RemoteViews;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.slider.Slider;
import com.lpnovi.radiation.config.WidgetConfig;
import com.lpnovi.radiation.media.MediaListenerService;
import com.lpnovi.radiation.media.MediaSessions;
import com.lpnovi.radiation.widget.RadiationWidgetProvider;
import com.lpnovi.radiation.widget.WidgetRenderer;

/**
 * Widget Studio: launcher entry point and the widget's configure activity. Edits one widget
 * instance at a time; every change is saved and pushed to the home screen immediately.
 */
public class StudioActivity extends AppCompatActivity {

    public static final String EXTRA_EDIT_WIDGET = "com.lpnovi.radiation.EDIT_WIDGET";

    private int widgetId = AppWidgetManager.INVALID_APPWIDGET_ID;
    private WidgetConfig config = new WidgetConfig();
    /** Suppresses listeners while controls are being populated from config. */
    private boolean binding;
    private boolean configuring;

    private FrameLayout preview;
    private MaterialButtonToggleGroup styleGroup, tapGroup;
    private Slider opacity;
    private MaterialSwitch showArt, showPrevious, showNext;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_studio);

        Intent intent = getIntent();
        if (AppWidgetManager.ACTION_APPWIDGET_CONFIGURE.equals(intent.getAction())) {
            configuring = true;
            widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID);
            // Backing out of first-time configuration cancels the widget placement, per the AppWidget contract.
            setResult(RESULT_CANCELED, resultIntent());
            View done = findViewById(R.id.done);
            done.setVisibility(View.VISIBLE);
            done.setOnClickListener(x -> {
                setResult(RESULT_OK, resultIntent());
                finish();
            });
        } else {
            widgetId = intent.getIntExtra(EXTRA_EDIT_WIDGET, AppWidgetManager.INVALID_APPWIDGET_ID);
        }

        preview = findViewById(R.id.preview);
        styleGroup = findViewById(R.id.style_group);
        tapGroup = findViewById(R.id.tap_group);
        opacity = findViewById(R.id.opacity);
        showArt = findViewById(R.id.show_art);
        showPrevious = findViewById(R.id.show_previous);
        showNext = findViewById(R.id.show_next);

        findViewById(R.id.access_grant).setOnClickListener(x -> openAccessSettings());

        styleGroup.addOnButtonCheckedListener((g, id, checked) -> {
            if (checked) edit(() -> config.style = id == R.id.style_material_you
                    ? WidgetConfig.Style.MATERIAL_YOU : WidgetConfig.Style.AMOLED);
        });
        tapGroup.addOnButtonCheckedListener((g, id, checked) -> {
            if (!checked) return;
            edit(() -> config.tapAction = id == R.id.tap_spotify ? WidgetConfig.TapAction.SPOTIFY
                    : id == R.id.tap_nothing ? WidgetConfig.TapAction.NOTHING
                    : WidgetConfig.TapAction.ACTIVE_APP);
        });
        opacity.addOnChangeListener((s, value, fromUser) ->
                edit(() -> config.backgroundAlpha = Math.round(value * 2.55f)));
        showArt.setOnCheckedChangeListener((b, on) -> edit(() -> config.showArt = on));
        showPrevious.setOnCheckedChangeListener((b, on) -> edit(() -> config.showPrevious = on));
        showNext.setOnCheckedChangeListener((b, on) -> edit(() -> config.showNext = on));
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Access may have just been granted in Settings, and widgets may have been added or removed.
        findViewById(R.id.access_card).setVisibility(MediaSessions.hasAccess(this) ? View.GONE : View.VISIBLE);
        bindWidgetPicker();
        WidgetRenderer.updateAll(this);
    }

    private void bindWidgetPicker() {
        int[] ids = AppWidgetManager.getInstance(this)
                .getAppWidgetIds(new ComponentName(this, RadiationWidgetProvider.class));
        boolean found = false;
        for (int id : ids) found |= id == widgetId;
        // A widget being configured may not be listed yet on some launchers; never swap it out.
        if (!found && !configuring) widgetId = ids.length > 0 ? ids[0] : widgetId;

        boolean editable = widgetId != AppWidgetManager.INVALID_APPWIDGET_ID;
        findViewById(R.id.no_widgets).setVisibility(editable ? View.GONE : View.VISIBLE);
        findViewById(R.id.editor).setVisibility(editable ? View.VISIBLE : View.GONE);

        ChipGroup picker = findViewById(R.id.widget_picker);
        picker.setOnCheckedStateChangeListener(null);
        picker.removeAllViews();
        picker.setVisibility(ids.length > 1 ? View.VISIBLE : View.GONE);
        for (int i = 0; i < ids.length; i++) {
            Chip chip = new Chip(this);
            chip.setText(getString(R.string.widget_n, i + 1));
            chip.setCheckable(true);
            chip.setId(View.generateViewId());
            chip.setTag(ids[i]);
            picker.addView(chip);
            if (ids[i] == widgetId) picker.check(chip.getId());
        }
        picker.setOnCheckedStateChangeListener((g, checked) -> {
            if (checked.isEmpty()) return;
            widgetId = (int) g.findViewById(checked.get(0)).getTag();
            bindConfig();
        });
        bindConfig();
    }

    private void bindConfig() {
        config = WidgetConfig.load(this, widgetId);
        binding = true;
        styleGroup.check(config.style == WidgetConfig.Style.MATERIAL_YOU ? R.id.style_material_you : R.id.style_amoled);
        tapGroup.check(config.tapAction == WidgetConfig.TapAction.SPOTIFY ? R.id.tap_spotify
                : config.tapAction == WidgetConfig.TapAction.NOTHING ? R.id.tap_nothing : R.id.tap_active);
        opacity.setValue(Math.round(config.backgroundAlpha / 2.55f / 5f) * 5f);
        showArt.setChecked(config.showArt);
        showPrevious.setChecked(config.showPrevious);
        showNext.setChecked(config.showNext);
        binding = false;
        renderPreview();
    }

    private void edit(Runnable change) {
        if (binding || widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return;
        change.run();
        config.save(this, widgetId);
        WidgetRenderer.updateAll(this);
        renderPreview();
    }

    /** The preview is the real widget: the same RemoteViews, inflated locally. */
    private void renderPreview() {
        RemoteViews views = WidgetRenderer.build(this, widgetId, config,
                MediaSessions.active(this), MediaSessions.hasAccess(this));
        preview.removeAllViews();
        preview.addView(views.apply(this, preview));
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
