package com.lpnovi.radiation.color;

import com.lpnovi.radiation.config.WidgetConfig;

/**
 * Pure color math (no Android dependencies, so it is unit-tested directly).
 *
 * Colors are opaque ARGB ints. {@link #NONE} (fully transparent) means "no color available".
 */
public final class ColorEngine {

    public static final int NONE = 0;
    /** Assumed wallpaper when the system can't tell us: launchers are dark more often than not. */
    public static final int UNKNOWN_WALLPAPER = 0xFF202124;

    static final int WHITE = 0xFFFFFFFF;
    /** Pure black: with pure white, it guarantees >= 4.58:1 against ANY background, even mid-gray. */
    static final int INK = 0xFF000000;
    /** WCAG AA for normal text. Used for text and the thin prev/next glyphs. */
    static final double TEXT_CONTRAST = 4.5;
    /** Below ~60% background opacity, wallpaper detail dominates what's behind the text. */
    static final int SEE_THROUGH_ALPHA = 153;

    private ColorEngine() {}

    /** Everything the renderer needs to paint one widget. */
    public static final class Theme {
        public int surface;        // background layer color (opaque; alpha applied separately)
        public int glow = NONE;    // gradient layer color fading in from the art side, or NONE
        public int effective;      // what the eye sees behind the foreground: surface blended over wallpaper
        public int text;
        public int textSecondary;  // opaque, pre-blended, still >= 4.5:1
        public int accent;         // control glyphs + visualizer
        public int placeholder;    // fallback art tile + icon tint
        public boolean darkForeground;  // effective background is light
    }

    /** Inputs that aren't part of the widget's config. */
    public static final class Inputs {
        public int seed = NONE;                     // album-art seed, or NONE
        public int wallpaper = UNKNOWN_WALLPAPER;   // wallpaper primary color
        public int myBg, myAccent;                  // Material You system colors
    }

    public static Theme theme(WidgetConfig c, Inputs in) {
        Theme t = new Theme();
        int seed = in.seed;
        boolean pastel = c.albumTone == WidgetConfig.AlbumTone.PASTEL;
        switch (c.background) {
            case AMOLED: t.surface = 0xFF000000; break;
            case MATERIAL_YOU: t.surface = opaque(in.myBg); break;
            case CUSTOM: t.surface = opaque(c.customColor); break;
            // A light haze of the wallpaper's own color: reads as frosted glass once translucent.
            case GLASS: t.surface = blend(WHITE, opaque(in.wallpaper), 0.35f); break;
            case ALBUM_GRADIENT:
                t.surface = seed == NONE ? albumFallback(pastel) : pastel ? pastelSurface(seed) : albumSurface(seed);
                t.glow = seed == NONE ? NONE : pastel ? pastelGlow(seed) : albumGlow(seed);
                break;
            default:
                t.surface = seed == NONE ? albumFallback(pastel) : pastel ? pastelSurface(seed) : albumSurface(seed);
                break;
        }
        float alpha = c.backgroundAlpha / 255f;
        t.effective = blend(t.surface, opaque(in.wallpaper), alpha);
        // Text sits over both gradient ends; judge readability against the worse of the two.
        int other = t.glow == NONE ? t.effective : blend(t.glow, opaque(in.wallpaper), alpha);
        // If the two gradient ends straddle the mid-tones (e.g. a light pastel glow made translucent
        // over a dark wallpaper), no text color can be readable on both. Readability wins: pull the
        // glow toward the surface until black or white text works across the whole gradient.
        for (float k = 0.1f; t.glow != NONE && bestTextContrast(t.effective, other) < TEXT_CONTRAST && k <= 1f; k += 0.1f) {
            t.glow = blend(t.surface, t.glow, k);
            other = blend(t.glow, opaque(in.wallpaper), alpha);
        }
        t.darkForeground = minContrast(INK, t.effective, other) > minContrast(WHITE, t.effective, other);
        t.text = t.darkForeground ? INK : WHITE;
        int secondary = blend(t.text, t.effective, 0.72f);
        // Mostly-transparent surface: the real pixels behind the text are local wallpaper detail we
        // can't see (only its overall color), so keep full strength rather than a dimmed tone.
        boolean seeThrough = c.backgroundAlpha < SEE_THROUGH_ALPHA;
        t.textSecondary = !seeThrough && minContrast(secondary, t.effective, other) >= TEXT_CONTRAST
                ? secondary : t.text;

        int accentSeed;
        switch (c.accent) {
            case MATERIAL_YOU: accentSeed = opaque(in.myAccent); break;
            case ALBUM: accentSeed = seed == NONE ? t.text : pastel ? pastelAccent(seed) : tame(seed); break;
            default: accentSeed = t.text; break;
        }
        t.accent = ensureContrast(accentSeed, t.effective, other, TEXT_CONTRAST, t.text);
        t.placeholder = blend(t.text, t.effective, 0.12f);
        return t;
    }

    private static double bestTextContrast(int bgA, int bgB) {
        return Math.max(minContrast(INK, bgA, bgB), minContrast(WHITE, bgA, bgB));
    }

    private static double minContrast(int fg, int bgA, int bgB) {
        return Math.min(contrast(fg, bgA), contrast(fg, bgB));
    }

    /**
     * Picks a seed from palette swatches. Population-weighted, favoring moderately saturated,
     * mid-lightness colors so a large dull area doesn't win over the artwork's character, while a
     * tiny saturated speck can't win either. Monochrome artwork yields its dominant gray.
     */
    public static int pickSeed(int[] rgb, int[] population) {
        int dominant = -1;
        for (int i = 0; i < rgb.length; i++) {
            if (dominant < 0 || population[i] > population[dominant]) dominant = i;
        }
        if (dominant < 0) return NONE;
        int best = -1;
        double bestScore = 0;
        for (int i = 0; i < rgb.length; i++) {
            float[] hsl = toHsl(rgb[i]);
            // Grays, near-black, near-white and specks (< 3% of the dominant area) can't define the theme.
            if (hsl[1] < MONO_SATURATION || hsl[2] < 0.06f || hsl[2] > 0.96f
                    || population[i] < population[dominant] * 0.03) continue;
            double lightnessWeight = Math.max(0.15, 1 - Math.abs(hsl[2] - 0.5) * 1.6);
            double score = population[i] * (0.25 + Math.min(hsl[1], 0.8)) * lightnessWeight;
            if (score > bestScore) {
                bestScore = score;
                best = i;
            }
        }
        return opaque(rgb[best >= 0 ? best : dominant]);
    }

    static final float MONO_SATURATION = 0.12f;

    /** Clamps garish saturation; near-gray seeds become true gray so they don't tint oddly. */
    static int tame(int seed) {
        float[] hsl = toHsl(seed);
        hsl[1] = hsl[1] < MONO_SATURATION ? 0f : Math.min(hsl[1], 0.75f);
        return fromHsl(hsl[0], hsl[1], hsl[2]);
    }

    /** A near-black surface carrying a hint of the artwork's hue: still AMOLED-friendly. */
    static int albumSurface(int seed) {
        float[] hsl = toHsl(seed);
        float s = hsl[1] < MONO_SATURATION ? 0f : Math.min(hsl[1], 0.45f);
        return fromHsl(hsl[0], s, 0.085f);
    }

    // --- Pastel tone ---
    //
    // Same seed and hue as Rich, but placed in fixed soft bands instead of being darkened: lowered
    // saturation with a floor (so it never goes muddy gray) and a ceiling (so it never looks candy),
    // and lightness set by role rather than taken from the artwork, so very dark, very bright and
    // saturated art all land in the same tasteful range. Monochrome art stays truly neutral.
    // Text and control colors are then chosen by the usual contrast rules, so a light pastel
    // surface gets dark text and deepened controls of the same hue.

    /** Light, softly tinted surface. */
    static int pastelSurface(int seed) {
        return pastel(seed, 0.6f, 0.20f, 0.40f, 0.90f, 0.93f);
    }

    /** Pastel glow: a little deeper and richer than the surface so the gradient stays visible. */
    static int pastelGlow(int seed) {
        return pastel(seed, 0.8f, 0.30f, 0.55f, 0.79f, 0.84f);
    }

    /** Soft accent (used as is on dark surfaces, deepened by contrast rules on light ones). */
    static int pastelAccent(int seed) {
        return pastel(seed, 0.7f, 0.30f, 0.58f, 0.78f, 0.80f);
    }

    /** Pastel without artwork: a clean warm-neutral light surface (deterministic). */
    static int albumFallback(boolean pastel) {
        return pastel ? 0xFFF1F0EE : 0xFF000000;
    }

    private static int pastel(int seed, float satScale, float satMin, float satMax, float light, float monoLight) {
        float[] hsl = toHsl(seed);
        if (hsl[1] < MONO_SATURATION) return fromHsl(hsl[0], 0f, monoLight);
        float s = Math.max(satMin, Math.min(satMax, hsl[1] * satScale));
        return fromHsl(hsl[0], s, light);
    }

    /** The gradient's glow: the artwork's color, deep enough that light text stays readable on it. */
    static int albumGlow(int seed) {
        float[] hsl = toHsl(seed);
        float s = hsl[1] < MONO_SATURATION ? 0f : Math.min(hsl[1], 0.6f);
        return fromHsl(hsl[0], s, 0.24f);
    }

    /**
     * Moves lightness away from the backgrounds, keeping hue and saturation, until contrast is met
     * against both. If that's impossible (backgrounds on opposite sides), uses {@code fallback}.
     */
    static int ensureContrast(int color, int bgA, int bgB, double target, int fallback) {
        if (minContrast(color, bgA, bgB) >= target) return color;
        float[] hsl = toHsl(color);
        boolean lighten = (luminance(bgA) + luminance(bgB)) / 2 < 0.18;
        for (int i = 0; i < 50; i++) {
            hsl[2] = Math.max(0f, Math.min(1f, hsl[2] + (lighten ? 0.02f : -0.02f)));
            int c = fromHsl(hsl[0], hsl[1], hsl[2]);
            if (minContrast(c, bgA, bgB) >= target) return c;
        }
        return fallback;
    }

    // --- sRGB / WCAG helpers ---

    static int opaque(int c) {
        return c | 0xFF000000;
    }

    static int r(int c) { return (c >> 16) & 0xFF; }
    static int g(int c) { return (c >> 8) & 0xFF; }
    static int b(int c) { return c & 0xFF; }

    static int rgb(int r, int g, int b) {
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    /** top over bottom with the given opacity of top. */
    public static int blend(int top, int bottom, float alpha) {
        float a = Math.max(0f, Math.min(1f, alpha));
        return rgb(Math.round(r(top) * a + r(bottom) * (1 - a)),
                Math.round(g(top) * a + g(bottom) * (1 - a)),
                Math.round(b(top) * a + b(bottom) * (1 - a)));
    }

    public static double luminance(int c) {
        return 0.2126 * lin(r(c)) + 0.7152 * lin(g(c)) + 0.0722 * lin(b(c));
    }

    private static double lin(int channel) {
        double v = channel / 255.0;
        return v <= 0.04045 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
    }

    public static double contrast(int a, int b) {
        double la = luminance(a), lb = luminance(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    static float[] toHsl(int c) {
        float rf = r(c) / 255f, gf = g(c) / 255f, bf = b(c) / 255f;
        float max = Math.max(rf, Math.max(gf, bf)), min = Math.min(rf, Math.min(gf, bf));
        float l = (max + min) / 2f, d = max - min, h = 0f, s = 0f;
        if (d > 0f) {
            s = d / (1f - Math.abs(2f * l - 1f));
            if (max == rf) h = ((gf - bf) / d) % 6f;
            else if (max == gf) h = (bf - rf) / d + 2f;
            else h = (rf - gf) / d + 4f;
            h *= 60f;
            if (h < 0) h += 360f;
        }
        return new float[]{h, Math.min(s, 1f), l};
    }

    static int fromHsl(float h, float s, float l) {
        float c = (1f - Math.abs(2f * l - 1f)) * s;
        float x = c * (1f - Math.abs((h / 60f) % 2f - 1f));
        float m = l - c / 2f;
        float rf, gf, bf;
        if (h < 60) { rf = c; gf = x; bf = 0; }
        else if (h < 120) { rf = x; gf = c; bf = 0; }
        else if (h < 180) { rf = 0; gf = c; bf = x; }
        else if (h < 240) { rf = 0; gf = x; bf = c; }
        else if (h < 300) { rf = x; gf = 0; bf = c; }
        else { rf = c; gf = 0; bf = x; }
        return rgb(clamp255(rf + m), clamp255(gf + m), clamp255(bf + m));
    }

    private static int clamp255(float v) {
        return Math.max(0, Math.min(255, Math.round(v * 255f)));
    }
}
