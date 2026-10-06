package com.lpnovi.radiation.widget;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SizesTest {

    @Test
    public void fitsOneRowAndKeepsHierarchyAcrossLauncherHeights() {
        for (float h = 48; h <= 140; h += 4) {
            WidgetRenderer.Sizes s = WidgetRenderer.Sizes.forHeight(h);
            float inner = h - 2 * s.pad;
            assertTrue("art fits at " + h, s.art <= Math.max(inner, 36));
            assertTrue("play fits at " + h, s.play <= Math.max(inner, 36));
            assertTrue("side fits at " + h, s.sideTouch <= Math.max(inner, 36));
            assertTrue("play is focal at " + h, s.play >= s.sideIcon * 1.6f);
            assertTrue("glyph inside disc at " + h, s.playIcon < s.play);
        }
    }

    @Test
    public void typicalOneUiRowGetsComfortableTargets() {
        WidgetRenderer.Sizes s = WidgetRenderer.Sizes.forHeight(96);
        assertTrue(s.sideTouch >= 48);
        assertTrue(s.play >= 56);
        // ~330dp wide 4-column widget: padding (22) + gaps (2) + elements must leave >= 80dp for track info.
        assertTrue(330 - 22 - 2 - s.art - s.play - 2 * s.sideTouch >= 80);
        assertTrue(s.art >= 64);
    }
}
