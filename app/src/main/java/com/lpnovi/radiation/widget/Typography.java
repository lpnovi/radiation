package com.lpnovi.radiation.widget;

/**
 * Text sizing for the metadata column. Pure (no Android types), unit-tested.
 *
 * Two independent fits:
 *  - Height: the title lines plus the artist line must fit the row's text area. If they don't,
 *    both shrink proportionally (within floors); if that still isn't enough, the artist line is
 *    dropped, then the second title line. Text never overlaps controls, shuffle or visualizer.
 *  - Width: a title that doesn't fit its line(s) is shrunk a little (at most MAX_SHRINK_SP, never
 *    below MIN_TITLE_SP) before the rest is ellipsized, so long names show more without every
 *    title becoming small. Short titles keep the chosen size.
 */
final class Typography {

    /** Line height relative to text size (includeFontPadding is off). */
    static final float LINE = 1.18f;
    /** Space between title and artist, dp (artist's top margin in the layout). */
    static final float GAP = 4f;
    static final float MIN_TITLE_SP = 11f, MIN_ARTIST_SP = 10f;
    static final float MAX_SHRINK_SP = 2.5f;
    /** A two-line title wraps at word boundaries, so it holds a bit less than twice one line. */
    static final float TWO_LINE_CAPACITY = 1.85f;

    float titleSp, artistSp;
    int titleLines;
    boolean showTitle, showArtist;

    /** Measures text width (px) at a size (sp). */
    interface Measure {
        float width(float sp);
    }

    /**
     * @param availableDp height of the text area
     * @param fontScale   the user's system font scale (sp to dp)
     */
    static Typography fitHeight(boolean showTitle, float titleSp, int titleLines, boolean showArtist,
                                float artistSp, float availableDp, float fontScale) {
        Typography t = new Typography();
        t.showTitle = showTitle;
        t.titleSp = titleSp;
        t.titleLines = Math.max(1, Math.min(2, titleLines));
        t.showArtist = showArtist;
        t.artistSp = artistSp;
        float needed = t.needed(fontScale);
        if (needed > availableDp) {
            float f = availableDp / needed;
            t.titleSp = Math.max(MIN_TITLE_SP, t.titleSp * f);
            t.artistSp = Math.max(MIN_ARTIST_SP, t.artistSp * f);
        }
        if (t.needed(fontScale) > availableDp && t.showArtist && t.showTitle) t.showArtist = false;
        if (t.needed(fontScale) > availableDp && t.titleLines == 2) t.titleLines = 1;
        return t;
    }

    /** Height the text block needs, dp. */
    float needed(float fontScale) {
        float h = 0;
        if (showTitle) h += titleLines * titleSp * fontScale * LINE;
        if (showArtist) h += (showTitle ? GAP : 0) + artistSp * fontScale * LINE;
        return h;
    }

    /** The artist stays visibly smaller than the title, even after the title shrank to fit. */
    static float artistFor(float titleSp, float artistSp) {
        return Math.max(MIN_ARTIST_SP, Math.min(artistSp, titleSp - HIERARCHY_SP));
    }

    static final float HIERARCHY_SP = 1.5f;

    /** Largest size in [max(maxSp - MAX_SHRINK_SP, MIN_TITLE_SP), maxSp] at which the title fits. */
    static float fitWidth(float maxSp, int lines, float availablePx, Measure measure) {
        float capacity = availablePx * (lines == 2 ? TWO_LINE_CAPACITY : 1f);
        float floor = Math.max(MIN_TITLE_SP, maxSp - MAX_SHRINK_SP);
        for (float sp = maxSp; sp > floor; sp -= 0.5f) {
            if (measure.width(sp) <= capacity) return sp;
        }
        return Math.min(maxSp, floor); // still too long: smallest allowed size, then ellipsize
    }
}
