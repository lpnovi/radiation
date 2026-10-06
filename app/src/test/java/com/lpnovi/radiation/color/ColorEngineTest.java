package com.lpnovi.radiation.color;

import static com.lpnovi.radiation.color.ColorEngine.contrast;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.lpnovi.radiation.config.WidgetConfig.Style;

import org.junit.Test;

public class ColorEngineTest {

    private static final int DARK_WALL = 0xFF101418;
    private static final int LIGHT_WALL = 0xFFF2F0EB;

    private static ColorEngine.Theme album(int seed, int alpha, int wallpaper) {
        return ColorEngine.theme(Style.ALBUM, alpha, seed, wallpaper, 0xFFEEEEEE, 0xFF4455AA);
    }

    /** Every theme, whatever the inputs, must keep text and controls readable. */
    private static void assertReadable(ColorEngine.Theme t) {
        assertTrue("text", contrast(t.text, t.effective) >= 4.5);
        assertTrue("secondary", contrast(t.textSecondary, t.effective) >= 4.5);
        assertTrue("accent", contrast(t.accent, t.effective) >= 4.5);
        assertTrue("glyph on disc", contrast(t.onAccent, t.accent) >= 3.0);
    }

    @Test
    public void readableAcrossSeedsOpacitiesAndWallpapers() {
        int[] seeds = {ColorEngine.NONE, 0xFFFF0000, 0xFF0000FF, 0xFFFFFF00, 0xFF050505, 0xFFFAFAFA,
                0xFF808080, 0xFF1DB954, 0xFF6A0DAD};
        for (Style style : Style.values()) {
            for (int seed : seeds) {
                for (int alpha : new int[]{0, 128, 255}) {
                    for (int wall : new int[]{DARK_WALL, LIGHT_WALL, ColorEngine.UNKNOWN_WALLPAPER}) {
                        assertReadable(ColorEngine.theme(style, alpha, seed, wall, 0xFFF3EDF7, 0xFF6750A4));
                    }
                }
            }
        }
    }

    @Test
    public void opacityOnlyChangesTheEffectiveBackground() {
        ColorEngine.Theme opaque = album(0xFF1DB954, 255, LIGHT_WALL);
        ColorEngine.Theme clear = album(0xFF1DB954, 0, LIGHT_WALL);
        assertEquals(opaque.surface, clear.surface);
        assertEquals(opaque.surface, opaque.effective); // 100%: the wallpaper is fully covered
        assertEquals(LIGHT_WALL, clear.effective);       // 0%: only the wallpaper is behind the text
        assertTrue(clear.darkForeground);                // so the text flips to dark ink
        assertFalse(opaque.darkForeground);
    }

    @Test
    public void albumAccentKeepsTheArtworkHue() {
        ColorEngine.Theme t = album(0xFF1DB954, 255, DARK_WALL); // Spotify green
        float[] hsl = ColorEngine.toHsl(t.accent);
        assertEquals(141f, hsl[0], 6f);
        assertTrue(hsl[1] > 0.3f);
    }

    @Test
    public void veryDarkArtworkIsLiftedNotUsedRaw() {
        ColorEngine.Theme t = album(0xFF0A0F2A, 255, DARK_WALL); // near-black navy
        assertTrue(ColorEngine.luminance(t.accent) > ColorEngine.luminance(0xFF0A0F2A));
        assertReadable(t);
    }

    @Test
    public void veryBrightArtworkStillContrastsOnLightBackground() {
        assertReadable(album(0xFFFFF8E1, 0, LIGHT_WALL));
    }

    @Test
    public void monochromeArtworkGivesNeutralAccentAndSurface() {
        ColorEngine.Theme t = album(0xFF7A7A78, 255, DARK_WALL);
        assertEquals(0f, ColorEngine.toHsl(t.accent)[1], 0.02f);
        assertEquals(0f, ColorEngine.toHsl(t.surface)[1], 0.02f);
    }

    @Test
    public void garishSaturationIsClamped() {
        assertTrue(ColorEngine.toHsl(ColorEngine.tame(0xFFFF00FF))[1] <= 0.76f);
    }

    @Test
    public void fallbackWithoutArtworkIsDeterministic() {
        ColorEngine.Theme a = album(ColorEngine.NONE, 255, DARK_WALL);
        ColorEngine.Theme b = album(ColorEngine.NONE, 255, DARK_WALL);
        assertEquals(0xFF000000, a.surface);
        assertEquals(a.accent, b.accent);
        assertEquals(a.text, a.accent);
    }

    @Test
    public void pickSeedPrefersCharacterfulColorOverDullDominant() {
        int[] rgb = {0xFF202020, 0xFFE63946, 0xFF3A3A3A};
        int[] pop = {6000, 900, 3000};
        assertEquals(0xFFE63946, ColorEngine.pickSeed(rgb, pop));
    }

    @Test
    public void pickSeedIgnoresTinySaturatedSpeck() {
        int[] rgb = {0xFF2B2D42, 0xFFFF0000};
        int[] pop = {9000, 40};
        assertEquals(0xFF2B2D42, ColorEngine.pickSeed(rgb, pop));
    }

    @Test
    public void pickSeedMonochromeReturnsDominantGray() {
        assertEquals(0xFF555555, ColorEngine.pickSeed(new int[]{0xFF555555, 0xFFDDDDDD}, new int[]{700, 300}));
        assertEquals(ColorEngine.NONE, ColorEngine.pickSeed(new int[0], new int[0]));
    }

    @Test
    public void hslRoundTrip() {
        for (int c : new int[]{0xFF1DB954, 0xFF6A0DAD, 0xFFFFFFFF, 0xFF000000, 0xFF808080}) {
            float[] h = ColorEngine.toHsl(c);
            int back = ColorEngine.fromHsl(h[0], h[1], h[2]);
            assertTrue(Math.abs(ColorEngine.r(c) - ColorEngine.r(back)) <= 1);
            assertTrue(Math.abs(ColorEngine.g(c) - ColorEngine.g(back)) <= 1);
            assertTrue(Math.abs(ColorEngine.b(c) - ColorEngine.b(back)) <= 1);
        }
    }
}
