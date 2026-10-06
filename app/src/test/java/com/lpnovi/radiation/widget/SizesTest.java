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
            // Touch targets may use the full row height (the row has no vertical padding).
            assertTrue("play fits at " + h, s.play <= h);
            assertTrue("side fits at " + h, s.sideTouch <= h);
            assertTrue("play glyph leads at " + h, s.playIcon >= s.sideIcon * 1.3f);
            assertTrue("play target >= 40dp at " + h, s.play >= 40);
            assertTrue("glyph inside its target at " + h, s.playIcon <= s.play - 8);
        }
    }

    @Test
    public void typicalOneUiRowGetsComfortableTargets() {
        WidgetRenderer.Sizes s = WidgetRenderer.Sizes.forHeight(96);
        assertTrue(s.sideTouch >= 48);
        assertTrue(s.play >= 56);
        assertTrue(s.playIcon >= 34);
        // ~330dp wide 4-column widget: padding (22) + gaps (2) + elements must leave >= 80dp for track info.
        assertTrue(330 - 22 - 2 - s.art - s.play - 2 * s.sideTouch >= 80);
        assertTrue(s.art >= 64);
    }
}
