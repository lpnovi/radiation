package com.lpnovi.radiation.widget;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class GlowBackgroundTest {

    private static final int W = 360, H = 100, RED = 16;
    // A typical album pair: near-black tinted surface and a deeper album color (crimson).
    private static final int SURFACE = 0xFF170B0D, GLOW = 0xFF5C1A24;

    private static float columnMean(int[] px, int x, int channelShift) {
        float sum = 0;
        for (int y = 0; y < H; y++) sum += (px[y * W + x] >> channelShift) & 0xFF;
        return sum / H;
    }

    /**
     * Banding shows up as plateaus separated by one-level steps. With dithering, the column average
     * changes gradually instead: no step between neighbouring columns comes close to a full level.
     */
    @Test
    public void glowFallsOffWithoutBandingSteps() {
        int[] px = GlowBackground.pixels(W, H, SURFACE, GLOW);
        for (int shift : new int[]{16, 8, 0}) {
            for (int x = 1; x < W; x++) {
                float step = Math.abs(columnMean(px, x, shift) - columnMean(px, x - 1, shift));
                assertTrue("step " + step + " at column " + x, step < 0.6f);
            }
        }
    }

    @Test
    public void glowPeaksBehindTheArtAndSettlesToTheSurface() {
        int[] px = GlowBackground.pixels(W, H, SURFACE, GLOW);
        int peak = Math.round(W * GlowBackground.CENTER_X);
        assertTrue(Math.abs(columnMean(px, peak, RED) - ((GLOW >> 16) & 0xFF)) < 8);
        assertTrue(Math.abs(columnMean(px, W - 1, RED) - ((SURFACE >> 16) & 0xFF)) < 2);
    }

    @Test
    public void sameInputsGiveTheSameImage() {
        // Re-renders must never shimmer: the dither is deterministic.
        assertArrayEquals(GlowBackground.pixels(64, 16, SURFACE, GLOW), GlowBackground.pixels(64, 16, SURFACE, GLOW));
    }

    @Test
    public void legacyCornersAreRoundedAndAntialiased() {
        int[] px = GlowBackground.pixels(W, H, SURFACE, GLOW);
        GlowBackground.roundCorners(px, W, H, 28);
        assertTrue((px[0] >>> 24) == 0);                         // outside the curve: transparent
        assertTrue((px[(H / 2) * W + W / 2] >>> 24) == 0xFF);   // body: opaque
        boolean partial = false;                                  // edge: some pixels partially covered
        for (int x = 0; x < 28; x++) {
            int a = px[x] >>> 24;
            partial |= a > 0 && a < 0xFF;
        }
        assertTrue(partial);
    }
}
