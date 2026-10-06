package com.lpnovi.radiation.widget;

import android.graphics.Bitmap;

/**
 * The Album Glow surface as one small bitmap: the artwork's color glowing out from behind the art,
 * falling off smoothly into the tinted near-black.
 *
 * Why a bitmap rather than a gradient drawable: a dark, shallow gradient spans only a handful of
 * 8-bit levels across the widget, so it bands into visible stripes; a multi-stop gradient adds a
 * visible "knee" at each stop; and a gradient layered over the surface double-antialiases the
 * rounded edge into a faint rim. Here the falloff is a single Gaussian (no stops), the result is
 * dithered with triangular noise of about one level (which dissolves bands into grain finer than the
 * eye resolves), and it is one layer clipped to the widget's outline.
 *
 * Rendered at 1px per dp (about 360x100, ~140 KB) and scaled up by the launcher; bilinear
 * scaling keeps the dither effective while keeping the RemoteViews payload small.
 */
final class GlowBackground {

    /** Glow center: behind the artwork, which sits at the left of the row. */
    static final float CENTER_X = 0.13f;
    /** Horizontal reach as a fraction of width; vertical reach as a fraction of height. */
    static final float REACH_X = 0.62f, REACH_Y = 1.5f;

    private GlowBackground() {}

    private static String cachedKey;
    private static Bitmap cached;

    /** @param cornerRadius rounds the bitmap itself (px = dp here); 0 when the host clips it instead. */
    static synchronized Bitmap get(int width, int height, int surface, int glow, float cornerRadius) {
        String key = width + "x" + height + ":" + surface + ":" + glow + ":" + cornerRadius;
        if (key.equals(cachedKey)) return cached;
        int[] px = pixels(width, height, surface, glow);
        if (cornerRadius > 0) roundCorners(px, width, height, cornerRadius);
        Bitmap b = Bitmap.createBitmap(px, width, height, Bitmap.Config.ARGB_8888);
        cachedKey = key;
        cached = b;
        return b;
    }

    /** Opaque ARGB pixels, row-major. Pure: unit-tested for smoothness. */
    static int[] pixels(int width, int height, int surface, int glow) {
        int[] out = new int[width * height];
        float sr = (surface >> 16) & 0xFF, sg = (surface >> 8) & 0xFF, sb = surface & 0xFF;
        float dr = ((glow >> 16) & 0xFF) - sr, dg = ((glow >> 8) & 0xFF) - sg, db = (glow & 0xFF) - sb;
        // Deterministic noise: the same inputs give the same image, so re-renders never shimmer.
        int seed = 0x2F6E2B1;
        for (int y = 0; y < height; y++) {
            float ny = ((y + 0.5f) / height - 0.5f) / REACH_Y;
            for (int x = 0; x < width; x++) {
                float nx = ((x + 0.5f) / width - CENTER_X) / REACH_X;
                float f = (float) Math.exp(-2.6 * (nx * nx + ny * ny));
                seed = seed * 1664525 + 1013904223;
                float n1 = ((seed >>> 8) & 0xFFFF) / 65536f;
                seed = seed * 1664525 + 1013904223;
                float n2 = ((seed >>> 8) & 0xFFFF) / 65536f;
                float dither = n1 + n2 - 1f; // triangular, -1..1 levels
                out[y * width + x] = 0xFF000000
                        | channel(sr + dr * f + dither) << 16
                        | channel(sg + dg * f + dither) << 8
                        | channel(sb + db * f + dither);
            }
        }
        return out;
    }

    /**
     * Android 8-11 can't clip the glow to the widget outline from a RemoteViews layout
     * (clipToOutline is Android 12+), so the bitmap carries its own antialiased rounded corners.
     */
    static void roundCorners(int[] px, int width, int height, float radius) {
        float r = Math.min(radius, Math.min(width, height) / 2f);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                float cx = Math.max(r - (x + 0.5f), (x + 0.5f) - (width - r));
                float cy = Math.max(r - (y + 0.5f), (y + 0.5f) - (height - r));
                if (cx <= 0 || cy <= 0) continue; // not in a corner square
                float coverage = Math.max(0f, Math.min(1f, r - (float) Math.hypot(cx, cy) + 0.5f));
                int i = y * width + x;
                px[i] = (Math.round(coverage * 255) << 24) | (px[i] & 0x00FFFFFF);
            }
        }
    }

    private static int channel(float v) {
        return Math.max(0, Math.min(255, Math.round(v)));
    }
}
