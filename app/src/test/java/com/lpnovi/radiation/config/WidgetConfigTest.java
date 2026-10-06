package com.lpnovi.radiation.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import org.junit.Test;

public class WidgetConfigTest {

    @Test
    public void opacityPercentMapsToBackgroundAlpha() {
        assertEquals(0, WidgetConfig.alphaFromPercent(0));
        assertEquals(128, WidgetConfig.alphaFromPercent(50));
        assertEquals(255, WidgetConfig.alphaFromPercent(100));
        assertEquals(255, WidgetConfig.alphaFromPercent(140));
    }

    @Test
    public void percentRoundTripsThroughAlpha() {
        for (int p = 0; p <= 100; p++) {
            assertEquals(p, WidgetConfig.percentFromAlpha(WidgetConfig.alphaFromPercent(p)));
        }
    }

    @Test
    public void defaultsAreTheCleanPreset() {
        WidgetConfig c = new WidgetConfig();
        assertEquals(WidgetConfig.Background.ALBUM_TINT, c.background);
        assertEquals(WidgetConfig.Accent.ALBUM, c.accent);
        assertEquals(255, c.backgroundAlpha);
        assertEquals(WidgetConfig.Visualizer.WAVE, c.visualizer);
        assertFalse(c.showShuffle);
        assertFalse(c.outline);
    }

    @Test
    public void legacyStyleMigratesWithoutChangingLooks() {
        WidgetConfig amoled = new WidgetConfig();
        amoled.applyLegacyStyle("AMOLED");
        assertEquals(WidgetConfig.Background.AMOLED, amoled.background);
        assertEquals(WidgetConfig.Accent.MONO, amoled.accent);

        WidgetConfig you = new WidgetConfig();
        you.applyLegacyStyle("MATERIAL_YOU");
        assertEquals(WidgetConfig.Background.MATERIAL_YOU, you.background);
        assertEquals(WidgetConfig.Accent.MATERIAL_YOU, you.accent);

        WidgetConfig album = new WidgetConfig();
        album.applyLegacyStyle("ALBUM");
        assertEquals(WidgetConfig.Background.ALBUM_TINT, album.background);
        assertEquals(WidgetConfig.Accent.ALBUM, album.accent);
    }
}
