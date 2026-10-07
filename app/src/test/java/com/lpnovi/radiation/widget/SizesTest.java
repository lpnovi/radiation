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

    /** Corner shuffle: generous target, restrained glyph, never on top of the control below it. */
    @Test
    public void cornerShuffleFitsAboveTheNextButton() {
        for (float lift : new float[]{0, 5}) {
            for (float h = 80; h <= 140; h += 4) {
                WidgetRenderer.Sizes s = WidgetRenderer.Sizes.forHeight(h);
                WidgetRenderer.Corner c = WidgetRenderer.Corner.place(h, lift, s.sideIcon, 8 + s.sideWidth / 2);
                float nextGlyphTop = (h - lift - s.sideIcon) / 2;
                assertTrue("target width", c.height >= 28 && WidgetRenderer.Corner.WIDTH >= 56);
                assertTrue("glyph 15-20dp at " + h, c.glyph >= 15 && c.glyph <= 20);
                assertTrue("glyph inset from top edge at " + h, c.glyphTop >= 5);
                assertTrue("visible gap above next glyph at " + h,
                        c.glyphTop + c.glyph <= nextGlyphTop - 4);
                assertTrue("target stops above next glyph at " + h, c.height <= nextGlyphTop - 2
                        || c.height == 28);
                assertTrue("secondary to next", c.glyph < s.sideIcon);
            }
        }
    }

    @Test
    public void cornerShuffleCentersOverTheNextColumn() {
        WidgetRenderer.Corner c = WidgetRenderer.Corner.place(100, 5, 27, 32);
        assertTrue(Math.abs(c.marginEnd + WidgetRenderer.Corner.WIDTH / 2 - 32) < 0.01f);
        // Never pressed against the edge, even when the column is very close to it.
        assertTrue(WidgetRenderer.Corner.place(100, 5, 27, 10).marginEnd >= 2);
    }

    @Test
    public void typicalOneUiRowGetsComfortableTargets() {
        WidgetRenderer.Sizes s = WidgetRenderer.Sizes.forHeight(96);
        assertTrue(s.sideTouch >= 48);
        assertTrue(s.play >= 56);
        assertTrue(s.playIcon >= 34);
        // ~330dp wide 4-column widget: padding (22) + gaps (2) + elements must leave >= 80dp for track info.
        assertTrue(330 - 22 - 2 - s.art - s.playWidth - 2 * s.sideWidth >= 80);
        // Targets keep full height even though they are a touch narrower.
        assertTrue(s.sideWidth >= 44 && s.playWidth >= 52);
        assertTrue(s.art >= 64);
    }
}
