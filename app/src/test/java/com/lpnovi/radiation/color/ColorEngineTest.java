package com.lpnovi.radiation.color;

import static com.lpnovi.radiation.color.ColorEngine.blend;
import static com.lpnovi.radiation.color.ColorEngine.contrast;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import com.lpnovi.radiation.config.WidgetConfig;
import com.lpnovi.radiation.config.WidgetConfig.Accent;
import com.lpnovi.radiation.config.WidgetConfig.Background;

import org.junit.Test;

public class ColorEngineTest {

    private static final int DARK_WALL = 0xFF101418;
    private static final int LIGHT_WALL = 0xFFF2F0EB;

    private static ColorEngine.Theme theme(Background bg, Accent accent, int alpha, int seed, int wall, int custom) {
        WidgetConfig c = new WidgetConfig();
        c.background = bg;
        c.accent = accent;
        c.backgroundAlpha = alpha;
        c.customColor = custom;
        ColorEngine.Inputs in = new ColorEngine.Inputs();
        in.seed = seed;
        in.wallpaper = wall;
        in.myBg = 0xFFF3EDF7;
        in.myAccent = 0xFF6750A4;
        return ColorEngine.theme(c, in);
    }

    private static ColorEngine.Theme album(int seed, int alpha, int wallpaper) {
        return theme(Background.ALBUM_TINT, Accent.ALBUM, alpha, seed, wallpaper, 0);
    }

    /** Text and controls must be readable over every part of the background, gradient included. */
    private static void assertReadable(ColorEngine.Theme t, int alpha, int wall) {
        int[] behind = t.glow == ColorEngine.NONE ? new int[]{t.effective}
                : new int[]{t.effective, blend(t.glow, wall, alpha / 255f)};
        for (int bg : behind) {
            assertTrue("text", contrast(t.text, bg) >= 4.5);
            assertTrue("secondary", contrast(t.textSecondary, bg) >= 4.5);
            assertTrue("accent", contrast(t.accent, bg) >= 4.5);
        }
    }

    @Test
    public void readableForEveryBackgroundAccentSeedOpacityAndWallpaper() {
        int[] seeds = {ColorEngine.NONE, 0xFFFF0000, 0xFF0000FF, 0xFFFFFF00, 0xFF050505, 0xFFFAFAFA,
                0xFF808080, 0xFF1DB954, 0xFF6A0DAD};
        int[] customs = {0xFF000000, 0xFF1C1B1F, 0xFFE8E4DC, 0xFF0A84FF, 0xFF808080};
        for (Background bg : Background.values()) {
            for (Accent accent : Accent.values()) {
                for (int seed : seeds) {
                    for (int alpha : new int[]{0, 102, 128, 255}) {
                        for (int wall : new int[]{DARK_WALL, LIGHT_WALL, ColorEngine.UNKNOWN_WALLPAPER}) {
                            for (int custom : bg == Background.CUSTOM ? customs : new int[]{0}) {
                                assertReadable(theme(bg, accent, alpha, seed, wall, custom), alpha, wall);
                            }
                        }
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
    public void defaultConfigKeepsTheCleanAlbumLook() {
        ColorEngine.Inputs in = new ColorEngine.Inputs();
        in.seed = 0xFF1DB954;
        ColorEngine.Theme t = ColorEngine.theme(new WidgetConfig(), in);
        assertEquals(ColorEngine.NONE, t.glow);                         // no gradient by default
        assertTrue(ColorEngine.toHsl(t.surface)[2] < 0.1f);             // near-black surface
        assertEquals(141f, ColorEngine.toHsl(t.accent)[0], 6f);         // album-colored controls
    }

    @Test
    public void gradientGlowCarriesTheArtworkHue() {
        ColorEngine.Theme t = theme(Background.ALBUM_GRADIENT, Accent.ALBUM, 255, 0xFFE63946, DARK_WALL, 0);
        assertNotEquals(ColorEngine.NONE, t.glow);
        assertEquals(ColorEngine.toHsl(0xFFE63946)[0], ColorEngine.toHsl(t.glow)[0], 4f);
        assertTrue(ColorEngine.luminance(t.glow) > ColorEngine.luminance(t.surface));
        // Without artwork the gradient degrades to plain black, not a random color.
        assertEquals(ColorEngine.NONE, theme(Background.ALBUM_GRADIENT, Accent.ALBUM, 255,
                ColorEngine.NONE, DARK_WALL, 0).glow);
    }

    @Test
    public void lightCustomBackgroundFlipsToDarkText() {
        ColorEngine.Theme t = theme(Background.CUSTOM, Accent.MONO, 255, ColorEngine.NONE, DARK_WALL, 0xFFE8E4DC);
        assertTrue(t.darkForeground);
        assertEquals(0xFFE8E4DC, t.surface);
    }

    @Test
    public void glassIsAHazeOfTheWallpaper() {
        ColorEngine.Theme dark = theme(Background.GLASS, Accent.MONO, 102, ColorEngine.NONE, DARK_WALL, 0);
        assertTrue(ColorEngine.luminance(dark.surface) > ColorEngine.luminance(DARK_WALL));
        assertFalse(dark.darkForeground); // translucent over a dark wallpaper: still light text
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
