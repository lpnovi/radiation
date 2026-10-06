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
        public int effective;      // what the eye sees behind the foreground: surface blended over wallpaper
        public int text;
        public int textSecondary;  // opaque, pre-blended, still >= 4.5:1
        public int accent;         // play disc + prev/next glyphs
        public int onAccent;       // play/pause glyph on the disc
        public int placeholder;    // fallback art tile + icon tint
        public boolean darkForeground;  // effective background is light
    }

    /**
     * @param seed       album-art seed color or NONE
     * @param wallpaper  wallpaper primary color or UNKNOWN_WALLPAPER
     * @param myBg       Material You surface (only used for that style)
     * @param myAccent   Material You accent (only used for that style)
     */
    public static Theme theme(WidgetConfig.Style style, int backgroundAlpha, int seed,
                              int wallpaper, int myBg, int myAccent) {
        Theme t = new Theme();
        switch (style) {
            case MATERIAL_YOU: t.surface = opaque(myBg); break;
            case ALBUM: t.surface = seed == NONE ? 0xFF000000 : albumSurface(seed); break;
            default: t.surface = 0xFF000000; break;
        }
        t.effective = blend(t.surface, opaque(wallpaper), backgroundAlpha / 255f);
        t.darkForeground = contrast(INK, t.effective) > contrast(WHITE, t.effective);
        t.text = t.darkForeground ? INK : WHITE;
        int secondary = blend(t.text, t.effective, 0.72f);
        // Mostly-transparent surface: the real pixels behind the text are local wallpaper detail we
        // can't see (only its overall color), so keep full strength rather than a dimmed tone.
        boolean seeThrough = backgroundAlpha < SEE_THROUGH_ALPHA;
        t.textSecondary = !seeThrough && contrast(secondary, t.effective) >= TEXT_CONTRAST ? secondary : t.text;

        int accentSeed;
        switch (style) {
            case MATERIAL_YOU: accentSeed = opaque(myAccent); break;
            case ALBUM: accentSeed = seed == NONE ? t.text : tame(seed); break;
            default: accentSeed = t.text; break;
        }
        t.accent = ensureContrast(accentSeed, t.effective, TEXT_CONTRAST);
        t.onAccent = contrast(INK, t.accent) >= contrast(WHITE, t.accent) ? INK : WHITE;
        t.placeholder = blend(t.text, t.effective, 0.12f);
        return t;
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

    /** Moves lightness away from the background, keeping hue and saturation, until contrast is met. */
    static int ensureContrast(int color, int background, double target) {
        if (contrast(color, background) >= target) return color;
        float[] hsl = toHsl(color);
        boolean lighten = luminance(background) < 0.18;
        for (int i = 0; i < 50; i++) {
            hsl[2] = Math.max(0f, Math.min(1f, hsl[2] + (lighten ? 0.02f : -0.02f)));
            int c = fromHsl(hsl[0], hsl[1], hsl[2]);
            if (contrast(c, background) >= target) return c;
        }
        return lighten ? WHITE : INK;
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
