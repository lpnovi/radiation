package com.lpnovi.radiation.config;

import static org.junit.Assert.assertEquals;

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
    public void defaultsAreTheAlbumPresetFullyOpaque() {
        WidgetConfig c = new WidgetConfig();
        assertEquals(WidgetConfig.Style.ALBUM, c.style);
        assertEquals(255, c.backgroundAlpha);
    }
}
